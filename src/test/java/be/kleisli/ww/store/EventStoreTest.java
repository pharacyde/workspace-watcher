package be.kleisli.ww.store;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class EventStoreTest {

  @TempDir Path tmp;

  private Path database;
  private Path workspace;
  private WatcherProperties props;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    database = tmp.resolve("history/events.db");
    props = new WatcherProperties();
    props.setDatabase(database.toString());
    props.setWorkspace(workspace.toString());
  }

  /** A store wired to its own bus, as it is at runtime. */
  private record Wiring(EventBus bus, EventStore store) {}

  private Wiring open() {
    return open(new ActiveWorkspace(props));
  }

  private Wiring open(ActiveWorkspace active) {
    EventBus bus = new EventBus(props);
    EventStore store = new EventStore(props, active, bus, new ObjectMapper());
    store.open();
    return new Wiring(bus, store);
  }

  private int rowsContaining(String text) throws java.sql.SQLException {
    try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        java.sql.PreparedStatement s =
            c.prepareStatement(
                "SELECT COUNT(*) FROM event WHERE instr(summary, ?) > 0 OR instr(detail, ?) > 0")) {
      s.setString(1, text);
      s.setString(2, text);
      try (java.sql.ResultSet rs = s.executeQuery()) {
        return rs.getInt(1);
      }
    }
  }

  private static void publish(EventBus bus, String summary) {
    bus.publish(WatchEvent.of(WatchEvent.Source.FS, "CREATED").summary(summary));
  }

  @Test
  @DisplayName("a full write queue is counted, said once per run of drops, and the notice is kept")
  void countsAndAnnouncesDrops() {
    Wiring w = open();
    // Counted by a subscriber rather than read from replay(): the bus keeps 2,000 and each burst
    // below is ten times that, so the first notice would be gone from it by the second.
    List<WatchEvent> notices = new java.util.ArrayList<>();
    w.bus()
        .subscribe(
            e -> {
              if (e.type().equals("HISTORY_DROPPED")) {
                notices.add(e);
              }
            });
    // One more than the queue holds, with no flush in between: the last one has nowhere to go.
    for (int i = 0; i <= 20_000; i++) {
      publish(w.bus(), "burst-" + i);
    }
    assertThat(w.store().dropped()).isEqualTo(1);

    w.store().flush();
    assertThat(notices).hasSize(1);
    assertThat(notices.get(0).summary()).contains("1 event(s)");
    // The notice itself made it into the archive: the history says where it is incomplete.
    w.store().flush();
    assertThat(w.store().history(workspace.toString(), null, null, 30_000))
        .extracting(EventStore.Stored::type)
        .contains("HISTORY_DROPPED");

    // Quiet flushes do not repeat it; a new run of drops does.
    w.store().flush();
    assertThat(notices).hasSize(1);
    for (int i = 0; i <= 20_000; i++) {
      publish(w.bus(), "again-" + i);
    }
    w.store().flush();
    assertThat(notices).hasSize(2);
  }

  @Test
  @DisplayName("redactHistory rewrites only rows with a secret, once, without holding up the flush")
  void redactsHistoryInBatchesBetweenFlushes() throws Exception {
    Wiring w = open();
    String token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij";
    // 50k rows, every second one carrying the token in both columns; the queue holds 20k.
    for (int i = 0; i < 50_000; i++) {
      String text = i % 2 == 0 ? "export GH=" + token : "export GH=nothing";
      w.bus()
          .publish(
              WatchEvent.of(WatchEvent.Source.HOOK, "PostToolUse")
                  .summary("Bash  $ " + text)
                  .detail("payload", "{\"command\":\"" + text + "\"}"));
      if (i % 10_000 == 9_999) {
        w.store().flush();
      }
    }
    assertThat(w.store().dropped()).isZero();

    // A flush arriving mid-way must wait for one small transaction, not for the whole pass.
    java.util.concurrent.atomic.AtomicLong slowestFlushNanos =
        new java.util.concurrent.atomic.AtomicLong();
    java.util.concurrent.atomic.AtomicBoolean redacting =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    Thread flusher =
        new Thread(
            () -> {
              // Every 20 ms, 25 times the production cadence; a tight loop would measure the
              // unfairness of a Java monitor rather than the store.
              while (redacting.get()) {
                try {
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  return;
                }
                publish(w.bus(), "during");
                long start = System.nanoTime();
                w.store().flush();
                slowestFlushNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
              }
            });
    flusher.start();
    long start = System.nanoTime();
    int changed = w.store().redactHistory(new be.kleisli.ww.guard.SensitiveContentScanner());
    long millis = (System.nanoTime() - start) / 1_000_000;
    redacting.set(false);
    flusher.join();
    System.out.printf(
        "redactHistory: 50k rows in %d ms; slowest concurrent flush %.1f ms%n",
        millis, slowestFlushNanos.get() / 1e6);

    assertThat(changed).isEqualTo(25_000);
    assertThat(slowestFlushNanos.get() / 1_000_000).isLessThan(100);
    // The marker keeps four characters, so the search is for the token itself, not its prefix.
    assertThat(rowsContaining(token)).isZero();
    assertThat(rowsContaining("‹github-token:ghp_…›")).isEqualTo(25_000);
    // Idempotent: the markers are not secrets, so a second pass finds nothing to change.
    assertThat(w.store().redactHistory(new be.kleisli.ww.guard.SensitiveContentScanner())).isZero();
  }

  @Test
  @DisplayName("records what the bus publishes and reads it back oldest first")
  void recordsAndReadsBack() {
    Wiring w = open();
    publish(w.bus(), "one");
    publish(w.bus(), "two");
    w.store().flush();

    assertThat(w.store().history(null, null, null, 100))
        .extracting(EventStore.Stored::summary)
        .containsExactly("one", "two");
  }

  @Test
  @DisplayName("history outlives the process that recorded it")
  void survivesRestart() {
    // The whole point of the store: the in-memory buffer holds minutes, this holds weeks.
    Wiring first = open();
    publish(first.bus(), "before the restart");
    first.store().flush();
    first.store().close();

    Wiring second = open();
    assertThat(second.bus().replay()).isEmpty();
    assertThat(second.store().history(null, null, null, 100))
        .extracting(EventStore.Stored::summary)
        .containsExactly("before the restart");
  }

  @Test
  @DisplayName("keeps events of different workspaces apart")
  void separatesWorkspaces() throws IOException {
    Path other = Files.createDirectory(tmp.resolve("other"));

    Wiring w = open();
    publish(w.bus(), "in project");
    w.store().flush();

    assertThat(w.store().history(other.toString(), null, null, 100)).isEmpty();
    assertThat(w.store().history(workspace.toString(), null, null, 100)).hasSize(1);
  }

  @Test
  @DisplayName("a limit keeps the most recent window, not the oldest")
  void limitKeepsNewest() {
    Wiring w = open();
    for (int i = 0; i < 10; i++) {
      publish(w.bus(), "e" + i);
    }
    w.store().flush();

    assertThat(w.store().history(null, null, null, 3))
        .extracting(EventStore.Stored::summary)
        .containsExactly("e7", "e8", "e9");
  }

  @Test
  @DisplayName("filters on a time range, with since inclusive and until exclusive")
  void filtersByTimeRange() {
    Wiring w = open();
    publish(w.bus(), "only");
    w.store().flush();

    List<EventStore.Stored> all = w.store().history(null, null, null, 10);
    String ts = all.getFirst().ts();

    assertThat(w.store().history(null, ts, null, 10)).hasSize(1);
    assertThat(w.store().history(null, null, ts, 10)).isEmpty();
  }

  @Test
  @DisplayName("carries detail through as stored JSON rather than re-encoding it")
  void preservesDetail() {
    Wiring w = open();
    w.bus()
        .publish(
            WatchEvent.of(WatchEvent.Source.HOOK, "PostToolUse")
                .summary("x")
                .detail("tool", "Bash"));
    w.store().flush();

    assertThat(w.store().history(null, null, null, 10).getFirst().detail())
        .isEqualTo("{\"tool\":\"Bash\"}");
  }

  @Test
  @DisplayName(
      "sensitiveEvents lists only the sensitive GUARD rows of this workspace, newest first")
  void listsSensitiveEvents() throws IOException {
    Path other = Files.createDirectory(tmp.resolve("other"));
    Wiring w = open();
    w.bus().publish(WatchEvent.of(WatchEvent.Source.GUARD, "SENSITIVE_CONTENT").summary("first"));
    w.bus().publish(WatchEvent.of(WatchEvent.Source.GUARD, "FLAGGED").summary("a guard rule"));
    w.bus().publish(WatchEvent.of(WatchEvent.Source.HOOK, "PreToolUse").summary("the call"));
    w.bus().publish(WatchEvent.of(WatchEvent.Source.GUARD, "SENSITIVE_OUTBOUND").summary("second"));
    w.store().flush();
    // Another workspace's hit must not show up here.
    Wiring elsewhere = open(new ActiveWorkspace(propsFor(other)));
    elsewhere
        .bus()
        .publish(WatchEvent.of(WatchEvent.Source.GUARD, "SENSITIVE_CONTENT").summary("x"));
    elsewhere.store().flush();

    assertThat(w.store().sensitiveEvents(200))
        .extracting(EventStore.Stored::summary)
        .containsExactly("second", "first");
    assertThat(w.store().sensitiveEvents(1))
        .extracting(EventStore.Stored::summary)
        .containsExactly("second");
    // A nonsense limit is clamped rather than refused.
    assertThat(w.store().sensitiveEvents(0)).hasSize(1);
  }

  private WatcherProperties propsFor(Path workspace) {
    WatcherProperties p = new WatcherProperties();
    p.setDatabase(database.toString());
    p.setWorkspace(workspace.toString());
    return p;
  }

  @Test
  @DisplayName("sensitiveEvents is served by the (workspace, source, id) index, without a sort")
  void sensitiveEventsUseTheirIndex() throws Exception {
    // The plan of the very statement that runs: on (workspace, id) alone SQLite walks every row
    // of the workspace to find the few GUARD ones - 34ms against 0.1ms over 200k rows.
    open();
    List<String> plan = new java.util.ArrayList<>();
    try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        java.sql.PreparedStatement st =
            c.prepareStatement("EXPLAIN QUERY PLAN " + EventStore.SENSITIVE_SQL)) {
      st.setString(1, workspace.toString());
      st.setInt(2, 200);
      try (java.sql.ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          plan.add(rs.getString("detail"));
        }
      }
    }
    assertThat(plan).singleElement().asString().contains("event_workspace_source_id");
    assertThat(plan.getFirst()).doesNotContain("TEMP B-TREE");
  }

  @Test
  @DisplayName("counts activity per bucket and separates agent-caused events")
  void countsActivity() {
    Wiring w = open();
    for (int i = 0; i < 5; i++) {
      publish(w.bus(), "file " + i);
    }
    w.bus()
        .publish(
            WatchEvent.of(WatchEvent.Source.TRANSCRIPT, "TOOL_USE")
                .agent("claude-code")
                .summary("Bash  $ mvn test"));
    w.store().flush();

    String since = java.time.Instant.now().minusSeconds(60).toString();
    String until = java.time.Instant.now().plusSeconds(60).toString();
    List<EventStore.Bucket> buckets = w.store().activity(null, since, until, 4);

    // A thousand file events during a checkout is noise; the tool call is the story, so the two
    // are counted apart.
    assertThat(buckets).isNotEmpty();
    assertThat(buckets.stream().mapToInt(EventStore.Bucket::count).sum()).isEqualTo(6);
    assertThat(buckets.stream().mapToInt(EventStore.Bucket::agentCount).sum()).isEqualTo(1);
  }

  @Test
  @DisplayName("returns nothing rather than throwing on an unparseable range")
  void toleratesBadRange() {
    Wiring w = open();
    publish(w.bus(), "one");
    w.store().flush();

    assertThat(w.store().activity(null, "not-a-timestamp", "also-not", 4)).isEmpty();
    assertThat(w.store().activity(null, null, null, 4)).isEmpty();
  }

  @Test
  @DisplayName("runs without persistence when no database is configured")
  void disabledWithoutDatabase() {
    // The workspace is chosen while the database still points into the temp directory: choosing
    // it writes the active-workspace sidecar beside the database, and with none configured that
    // is the working directory - the project root, under Surefire. This test used to leave that
    // file one level above the project, which is how the sidecar bug was noticed at all.
    ActiveWorkspace active = new ActiveWorkspace(props);
    props.setDatabase("");
    Wiring w = open(active);
    publish(w.bus(), "nowhere");
    w.store().flush();

    assertThat(w.store().enabled()).isFalse();
    assertThat(w.store().history(null, null, null, 10)).isEmpty();
    assertThat(Files.exists(database)).isFalse();
  }

  @Test
  @DisplayName("keeps MCP and subagent attribution across the database round trip")
  void keepsAttribution() {
    Wiring w = open();
    w.bus()
        .publish(
            WatchEvent.of(WatchEvent.Source.TRANSCRIPT, "TOOL_USE")
                .summary("build")
                .mcpServer("jenkins")
                .subagent("Explore"));
    w.store().flush();

    // Without these columns a replayed event came back untagged, which does not read as "unknown"
    // but as "the main agent did this" - inventing attribution rather than declining to.
    assertThat(w.store().history(null, null, null, 100))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.mcpServer()).isEqualTo("jenkins");
              assertThat(e.subagent()).isEqualTo("Explore");
            });
  }

  @Test
  @DisplayName("adds the attribution columns to a database written before they existed")
  void migratesAnOlderDatabase() throws Exception {
    Files.createDirectories(database.getParent());
    try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        java.sql.Statement st = c.createStatement()) {
      st.executeUpdate(
          """
          CREATE TABLE event (
            id INTEGER PRIMARY KEY AUTOINCREMENT, seq TEXT NOT NULL, ts TEXT NOT NULL,
            source TEXT NOT NULL, type TEXT NOT NULL, summary TEXT, path TEXT, agent TEXT,
            session_id TEXT, detail TEXT, workspace TEXT NOT NULL)\
          """);
    }

    Wiring w = open();
    w.bus().publish(WatchEvent.of(WatchEvent.Source.HOOK, "TOOL_USE").subagent("fork"));
    w.store().flush();

    assertThat(w.store().history(null, null, null, 100))
        .singleElement()
        .satisfies(e -> assertThat(e.subagent()).isEqualTo("fork"));
  }

  @Test
  @DisplayName("opening twice does not fail on the migration it already applied")
  void migrationIsIdempotent() {
    open().store();
    Wiring second = open();
    second.bus().publish(WatchEvent.of(WatchEvent.Source.FS, "CREATED").summary("after"));
    second.store().flush();

    assertThat(second.store().history(null, null, null, 100))
        .extracting(EventStore.Stored::summary)
        .contains("after");
  }

  @Test
  @DisplayName("caps resource samples by count, not only by age")
  void capsMetrics() {
    props.setMaxStoredMetrics(10);
    Wiring w = open();
    for (int i = 0; i < 40; i++) {
      w.store().recordResources(workspace.toString(), 1.5, 2048);
    }

    // Samples are written on a schedule rather than when something changes, so the count says
    // nothing about how much happened: measured at 2833 rows in under three hours, roughly 740k a
    // month on an idle watcher. Age alone never catches up with that.
    w.store().prune();

    assertThat(countMetrics()).isLessThanOrEqualTo(11);
  }

  private int countMetrics() {
    try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        java.sql.Statement st = c.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM metric")) {
      return rs.next() ? rs.getInt(1) : -1;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
