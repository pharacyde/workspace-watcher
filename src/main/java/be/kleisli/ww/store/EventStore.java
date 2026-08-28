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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
      String mcpServer,
      String subagent,
      String detail,
      String workspace) {}

  /** Where the database lives, so a read can open its own connection to it. */
  private volatile Path databaseFile;

  private final WatcherProperties props;
  private final ActiveWorkspace active;
  private final EventBus bus;
  private final ObjectMapper mapper;

  private final BlockingQueue<Stored> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong dropped = new AtomicLong();

  /**
   * Volatile: written by open() and by the disable path when a migration did not take, and read
   * without the lock by enabled() from GraphQL threads.
   */
  private volatile Connection connection;

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
      // Spring's script runner rather than a wall of statement.execute calls: the schema lives
      // in db/schema.sql, where it is SQL rather than strings, and every statement in it is
      // idempotent so this is safe on every start.
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
      // Separately, and tolerating failure: see the comment at the top of migrate.sql.
      ScriptUtils.executeSqlScript(
          connection,
          new EncodedResource(new ClassPathResource("db/migrate.sql")),
          true,
          false,
          ScriptUtils.DEFAULT_COMMENT_PREFIX,
          ScriptUtils.DEFAULT_STATEMENT_SEPARATOR,
          ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
          ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
      connection.setAutoCommit(false);
      // continueOnError above cannot tell "the column was already there" from "the database is
      // read-only". Without this check the difference shows up only as a warning every 500 ms
      // while enabled() keeps claiming history is on - so the check is here, and a database that
      // did not come out right disables history loudly instead of losing it quietly.
      if (!hasColumns(connection, "mcp_server", "subagent")) {
        log.error(
            "history is disabled; {} is missing columns the migration should have added", file);
        connection.close();
        connection = null;
        return;
      }
      databaseFile = file;
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
            event.mcpServer(),
            event.subagent(),
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

  /*
   * Everything below that touches `connection` is synchronized. One SQLite connection is shared by
   * the flush, the hourly prune, the process sampler and every GraphQL query thread, and a JDBC
   * connection is not safe to use from two threads at once - two transactions interleaving on it
   * means one's rollback discards the other's work. Serialising them is cheap here: the writes are
   * batched and the reads are counted in SQL, so nobody holds the lock long.
   */

  private static boolean hasColumns(Connection c, String... required) throws SQLException {
    Set<String> present = new java.util.HashSet<>();
    try (PreparedStatement st = c.prepareStatement("PRAGMA table_info(event)");
        ResultSet rs = st.executeQuery()) {
      while (rs.next()) {
        present.add(rs.getString("name"));
      }
    }
    return present.containsAll(List.of(required));
  }

  /**
   * A connection of this reader's own.
   *
   * <p>The writes share one connection under one lock, because SQLite has one writer. Reads must
   * not join that queue: the schema turns on WAL precisely so a long read cannot block the writer,
   * and routing every GraphQL query through the writer's monitor would hand that back - an hourly
   * prune over millions of rows would stall the flush, whose queue then fills and drops the newest
   * events. Opening a connection is cheap against reading the rows it is about to read.
   */
  private Connection openForReading() throws SQLException {
    Path file = databaseFile;
    if (file == null) {
      throw new SQLException("history is not open");
    }
    // Not marked read-only: the driver rejects the flag once the connection exists, and pointing
    // it at SQLiteConfig for one hint is not worth the coupling. The three callers only SELECT.
    return DriverManager.getConnection("jdbc:sqlite:" + file);
  }

  @Scheduled(fixedDelayString = "${watcher.history-flush-ms:500}")
  public synchronized void flush() {
    if (connection == null || pending.isEmpty()) {
      return;
    }
    List<Stored> batch = new ArrayList<>(pending.size());
    pending.drainTo(batch);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO event
              (seq, ts, source, type, summary, path, agent, session_id, mcp_server,
               subagent, detail, workspace)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\
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
        statement.setString(9, e.mcpServer());
        statement.setString(10, e.subagent());
        statement.setString(11, e.detail());
        statement.setString(12, e.workspace());
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
    if (databaseFile == null) {
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
        SELECT seq, ts, source, type, summary, path, agent, session_id, mcp_server,
               subagent, detail, workspace
        FROM event
        WHERE workspace = ?
          AND (? IS NULL OR ts >= ?)
          AND (? IS NULL OR ts < ?)
        ORDER BY id DESC
        LIMIT ?\
        """;
    List<Stored> rows = new ArrayList<>();
    try (Connection reader = openForReading();
        PreparedStatement statement = reader.prepareStatement(sql)) {
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
                  rs.getString(10),
                  rs.getString(11),
                  rs.getString(12)));
        }
      }
    } catch (SQLException e) {
      log.warn("cannot read history: {}", e.toString());
      return List.of();
    }
    return rows.reversed();
  }

  /** One resource sample: CPU percent across the workspace's processes, and their total RSS. */
  public record ResourceBucket(int index, String from, double cpu, double memoryMb) {}

  /**
   * Records one resource sample.
   *
   * <p>Written straight through rather than queued: it happens once every couple of seconds, not
   * thousands of times a second like events, so the batching that protects the collectors would
   * only add latency to something that has none.
   */
  public synchronized void recordResources(String workspace, double cpu, long rssKb) {
    if (connection == null) {
      return;
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO metric (ts, workspace, cpu, rss_kb) VALUES (?, ?, ?, ?)")) {
      statement.setString(1, Instant.now().toString());
      statement.setString(2, workspace);
      statement.setDouble(3, cpu);
      statement.setLong(4, rssKb);
      statement.executeUpdate();
      connection.commit();
    } catch (SQLException e) {
      log.debug("cannot record resources: {}", e.toString());
    }
  }

  /** Averaged per slice rather than summed: a rate does not add up over a window. */
  public List<ResourceBucket> resourceActivity(String since, String until, int buckets) {
    if (databaseFile == null) {
      return List.of();
    }
    Path workspace = active.get();
    if (workspace == null) {
      return List.of();
    }
    long from;
    long to;
    try {
      from = Instant.parse(since).getEpochSecond();
      to = Instant.parse(until).getEpochSecond();
    } catch (DateTimeParseException e) {
      return List.of();
    }
    int slices = Math.clamp(buckets, 1, 2000);
    long width = Math.max(1, (to - from) / slices);

    String sql =
        """
        SELECT CAST((strftime('%s', ts) - ?) / ? AS INTEGER) AS bucket,
               AVG(cpu), AVG(rss_kb)
        FROM metric
        WHERE workspace = ? AND ts >= ? AND ts < ?
        GROUP BY bucket
        ORDER BY bucket\
        """;

    List<ResourceBucket> result = new ArrayList<>();
    try (Connection reader = openForReading();
        PreparedStatement statement = reader.prepareStatement(sql)) {
      statement.setLong(1, from);
      statement.setLong(2, width);
      statement.setString(3, workspace.toString());
      statement.setString(4, since);
      statement.setString(5, until);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          int index = rs.getInt(1);
          result.add(
              new ResourceBucket(
                  index,
                  Instant.ofEpochSecond(from + (long) index * width).toString(),
                  rs.getDouble(2),
                  rs.getDouble(3) / 1024d));
        }
      }
    } catch (SQLException e) {
      log.warn("cannot read resources: {}", e.toString());
      return List.of();
    }
    return result;
  }

  /** How many events fell in one slice of a timeline, and how many of those an agent caused. */
  public record Bucket(int index, String from, int count, int agentCount) {}

  /**
   * Activity density over a range, for a timeline to draw.
   *
   * <p>Counted in SQL rather than by fetching the rows. A month of history is millions of events
   * and a timeline needs a few hundred numbers, so pulling them across to count them would be the
   * expensive way to draw a small picture.
   *
   * <p>Agent-caused events are counted separately because the two densities mean different things:
   * a thousand file events during a checkout is noise, ten tool calls is the story.
   */
  public List<Bucket> activity(String workspace, String since, String until, int buckets) {
    if (databaseFile == null) {
      return List.of();
    }
    Path fallback = active.get();
    String target = workspace != null ? workspace : (fallback == null ? null : fallback.toString());
    if (target == null || since == null || until == null) {
      return List.of();
    }
    long from;
    long to;
    try {
      from = Instant.parse(since).getEpochSecond();
      to = Instant.parse(until).getEpochSecond();
    } catch (DateTimeParseException e) {
      // A client sending a malformed range gets an empty timeline, not a failed request.
      log.debug("unparseable activity range {}..{}", since, until);
      return List.of();
    }
    int slices = Math.clamp(buckets, 1, 2000);
    long width = Math.max(1, (to - from) / slices);

    String sql =
        """
        SELECT CAST((strftime('%s', ts) - ?) / ? AS INTEGER) AS bucket,
               COUNT(*),
               SUM(CASE WHEN source IN ('TRANSCRIPT', 'HOOK') THEN 1 ELSE 0 END)
        FROM event
        WHERE workspace = ? AND ts >= ? AND ts < ?
        GROUP BY bucket
        ORDER BY bucket\
        """;

    List<Bucket> result = new ArrayList<>();
    try (Connection reader = openForReading();
        PreparedStatement statement = reader.prepareStatement(sql)) {
      statement.setLong(1, from);
      statement.setLong(2, width);
      statement.setString(3, target);
      statement.setString(4, since);
      statement.setString(5, until);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          int index = rs.getInt(1);
          result.add(
              new Bucket(
                  index,
                  Instant.ofEpochSecond(from + (long) index * width).toString(),
                  rs.getInt(2),
                  rs.getInt(3)));
        }
      }
    } catch (SQLException | RuntimeException e) {
      log.warn("cannot read activity: {}", e.toString());
      return List.of();
    }
    return result;
  }

  /**
   * Trims history once an hour, by age and by count.
   *
   * <p>Age alone is not enough. A row costs roughly 384 bytes measured, so a busy month runs to
   * gigabytes; the row cap is the backstop for when "thirty days" turns out to mean far more events
   * than anyone expected.
   */
  @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
  public synchronized void prune() {
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
      try (PreparedStatement statement =
          connection.prepareStatement("DELETE FROM metric WHERE ts < ?")) {
        statement.setString(1, cutoff);
        removed += statement.executeUpdate();
      }
      // Age alone is not enough here. Resources are sampled on a fixed schedule rather than when
      // something changes - deliberately, because a steady build keeps the same processes for
      // minutes and those are the minutes worth looking at - so the table grows whether anything
      // happens or not: measured at 2833 rows in 2h44m, about 740k a month on one idle watcher.
      // event has had a row cap for exactly this reason; metric needs the same.
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              DELETE FROM metric WHERE id <= (
                SELECT id FROM metric ORDER BY id DESC LIMIT 1 OFFSET ?)\
              """)) {
        statement.setInt(1, props.getMaxStoredMetrics());
        removed += statement.executeUpdate();
      }
      connection.commit();
      if (removed > 0) {
        log.info("pruned {} event(s)", removed);
        try (PreparedStatement vacuum = connection.prepareStatement("PRAGMA incremental_vacuum")) {
          // Without this the file keeps the space it no longer needs.
          vacuum.execute();
        }
      }
    } catch (SQLException e) {
      log.warn("cannot prune history: {}", e.toString());
    }
  }

  @PreDestroy
  synchronized void close() {
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
