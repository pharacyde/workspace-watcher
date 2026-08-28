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
    EventBus bus = new EventBus(props);
    EventStore store = new EventStore(props, new ActiveWorkspace(props), bus, new ObjectMapper());
    store.open();
    return new Wiring(bus, store);
  }

  private static void publish(EventBus bus, String summary) {
    bus.publish(WatchEvent.of(WatchEvent.Source.FS, "CREATED").summary(summary));
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
    props.setDatabase("");
    Wiring w = open();
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
}
