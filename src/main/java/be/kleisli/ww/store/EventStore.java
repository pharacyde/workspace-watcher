package be.kleisli.ww.store;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable history, so the feed is no longer only what happened since the process started.
 *
 * <p>Everything the in-memory buffer holds is also written here, tagged with the workspace it
 * belonged to. That makes a restart survivable, and it is the thing scrubbing back through a
 * session needs — the ring buffer holds minutes, this holds weeks.
 *
 * <p>Writes are queued and flushed in batches on a scheduler. Recording must never slow down the
 * collectors: a burst of file events would otherwise pay a disk transaction each. If the queue does
 * fill, the newest events are dropped and the loss is logged, because stalling a collector to
 * protect the archive would be the wrong way round.
 */
@Service
public class EventStore {

  private static final Logger log = LoggerFactory.getLogger(EventStore.class);
  private static final int QUEUE_CAPACITY = 20_000;

  /** One row as it is stored and returned, matching the wire shape rather than the domain type. */
  public record Stored(
      String seq,
      String ts,
      String source,
      String type,
      String summary,
      String path,
      String agent,
      String sessionId,
      String detail,
      String workspace) {}

  private final WatcherProperties props;
  private final ActiveWorkspace active;
  private final EventBus bus;
  private final ObjectMapper mapper;

  private final BlockingQueue<Stored> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong dropped = new AtomicLong();

  private Connection connection;
  private Runnable unsubscribe;

  public EventStore(
      WatcherProperties props, ActiveWorkspace active, EventBus bus, ObjectMapper mapper) {
    this.props = props;
    this.active = active;
    this.bus = bus;
    this.mapper = mapper;
  }

  public boolean enabled() {
    return connection != null;
  }

  @PostConstruct
  void open() {
    String location = props.getDatabase();
    if (location == null || location.isBlank()) {
      log.info("history is disabled; watcher.database is empty");
      return;
    }
    try {
      Path file = Path.of(location).toAbsolutePath().normalize();
      Files.createDirectories(file.getParent());
      connection = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (Statement statement = connection.createStatement()) {
        // WAL so a long read cannot block the writer, and vice versa.
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
        statement.execute("PRAGMA synchronous=NORMAL");
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS event (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              seq TEXT NOT NULL,
              ts TEXT NOT NULL,
              source TEXT NOT NULL,
              type TEXT NOT NULL,
              summary TEXT,
              path TEXT,
              agent TEXT,
              session_id TEXT,
              detail TEXT,
              workspace TEXT NOT NULL)\
            """);
        statement.execute("CREATE INDEX IF NOT EXISTS event_workspace_ts ON event (workspace, ts)");
        // The common query is "the most recent N for this workspace", which orders by id. The
        // (workspace, ts) index cannot serve that ordering, so without this one SQLite filtered
        // by workspace and then sorted the whole partition.
        statement.execute("CREATE INDEX IF NOT EXISTS event_workspace_id ON event (workspace, id)");
      }
      connection.setAutoCommit(false);
      log.info("recording history to {}", file);
    } catch (IOException | SQLException e) {
      log.warn("history is disabled; cannot open {}: {}", location, e.toString());
      connection = null;
      return;
    }
    unsubscribe = bus.subscribe(this::record);
  }

  private void record(WatchEvent event) {
    Path workspace = active.get();
    if (workspace == null) {
      return;
    }
    String detail = null;
    if (event.detail() != null) {
      try {
        detail = mapper.writeValueAsString(event.detail());
      } catch (RuntimeException e) {
        detail = null;
      }
    }
    Stored stored =
        new Stored(
            Long.toString(event.seq()),
            event.ts().toString(),
            event.source().name(),
            event.type(),
            event.summary(),
            event.path(),
            event.agent(),
            event.sessionId(),
            detail,
            workspace.toString());
    if (!pending.offer(stored)) {
      // Dropping the newest rather than blocking: a collector must never wait on the archive.
      long total = dropped.incrementAndGet();
      if (total % 1000 == 1) {
        log.warn("history queue is full; dropped {} event(s) so far", total);
      }
    }
  }

  @Scheduled(fixedDelayString = "${watcher.history-flush-ms:500}")
  public void flush() {
    if (connection == null || pending.isEmpty()) {
      return;
    }
    List<Stored> batch = new ArrayList<>(pending.size());
    pending.drainTo(batch);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO event
              (seq, ts, source, type, summary, path, agent, session_id, detail, workspace)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
            """)) {
      for (Stored e : batch) {
        statement.setString(1, e.seq());
        statement.setString(2, e.ts());
        statement.setString(3, e.source());
        statement.setString(4, e.type());
        statement.setString(5, e.summary());
        statement.setString(6, e.path());
        statement.setString(7, e.agent());
        statement.setString(8, e.sessionId());
        statement.setString(9, e.detail());
        statement.setString(10, e.workspace());
        statement.addBatch();
      }
      statement.executeBatch();
      connection.commit();
    } catch (SQLException e) {
      log.warn("cannot write history: {}", e.toString());
      try {
        connection.rollback();
      } catch (SQLException ignored) {
        // Nothing further to do; the batch is lost either way.
      }
    }
  }

  /**
   * Recorded events for a workspace, oldest first.
   *
   * @param workspace absolute path, or null for the workspace currently being watched
   * @param since inclusive ISO-8601 lower bound, or null
   * @param until exclusive ISO-8601 upper bound, or null
   */
  public List<Stored> history(String workspace, String since, String until, int limit) {
    if (connection == null) {
      return List.of();
    }
    Path fallback = active.get();
    String target = workspace != null ? workspace : (fallback == null ? null : fallback.toString());
    if (target == null) {
      return List.of();
    }

    // Newest rows are selected and then reversed, so a limit keeps the most recent window rather
    // than the oldest one.
    String sql =
        """
        SELECT seq, ts, source, type, summary, path, agent, session_id, detail, workspace
        FROM event
        WHERE workspace = ?
          AND (? IS NULL OR ts >= ?)
          AND (? IS NULL OR ts < ?)
        ORDER BY id DESC
        LIMIT ?\
        """;
    List<Stored> rows = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, target);
      statement.setString(2, since);
      statement.setString(3, since);
      statement.setString(4, until);
      statement.setString(5, until);
      statement.setInt(6, Math.clamp(limit, 1, 20_000));
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Stored(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9),
                  rs.getString(10)));
        }
      }
    } catch (SQLException e) {
      log.warn("cannot read history: {}", e.toString());
      return List.of();
    }
    return rows.reversed();
  }

  /**
   * Trims history once an hour, by age and by count.
   *
   * <p>Age alone is not enough. A row costs roughly 384 bytes measured, so a busy month runs to
   * gigabytes; the row cap is the backstop for when "thirty days" turns out to mean far more events
   * than anyone expected.
   */
  @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
  public void prune() {
    if (connection == null) {
      return;
    }
    String cutoff = Instant.now().minus(props.getRetentionDays(), ChronoUnit.DAYS).toString();
    try {
      int removed;
      try (PreparedStatement statement =
          connection.prepareStatement("DELETE FROM event WHERE ts < ?")) {
        statement.setString(1, cutoff);
        removed = statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              DELETE FROM event WHERE id <= (
                SELECT id FROM event ORDER BY id DESC LIMIT 1 OFFSET ?)\
              """)) {
        statement.setInt(1, props.getMaxStoredEvents());
        removed += statement.executeUpdate();
      }
      connection.commit();
      if (removed > 0) {
        log.info("pruned {} event(s)", removed);
        try (Statement statement = connection.createStatement()) {
          // Without this the file keeps the space it no longer needs.
          statement.execute("PRAGMA incremental_vacuum");
        }
      }
    } catch (SQLException e) {
      log.warn("cannot prune history: {}", e.toString());
    }
  }

  @PreDestroy
  void close() {
    if (unsubscribe != null) {
      unsubscribe.run();
    }
    flush();
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException e) {
        log.debug("cannot close history: {}", e.toString());
      }
    }
  }
}
