package be.kleisli.ww.claude;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SessionRegistryTest {

  @TempDir Path tmp;

  private Path workspace;
  private Path projects;
  private WatcherProperties props;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    Path claudeHome = Files.createDirectory(tmp.resolve("claude"));
    projects = Files.createDirectories(claudeHome.resolve("projects"));

    props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    props.setClaudeHome(claudeHome.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
  }

  private static String escape(Path path) {
    return path.toString().replaceAll("[^a-zA-Z0-9]", "-");
  }

  private Path transcript(String directory, String session, String... lines) throws IOException {
    Path dir = Files.createDirectories(projects.resolve(directory));
    Path file = dir.resolve(session + ".jsonl");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return file;
  }

  private SessionRegistry registry() {
    ActiveWorkspace active = new ActiveWorkspace(props);
    SessionRegistry registry =
        new SessionRegistry(new TranscriptLocator(props, active), props, new ObjectMapper());
    registry.scan();
    return registry;
  }

  @Test
  @DisplayName("finds the sessions of this workspace")
  void findsSessions() throws IOException {
    transcript(escape(workspace), "session-a", "{}");

    assertThat(registry().current())
        .singleElement()
        .satisfies(e -> assertThat(e.id()).isEqualTo("session-a"));
  }

  @Test
  @DisplayName("includes a session started in a subdirectory")
  void includesSubdirectorySessions() throws IOException {
    transcript(escape(workspace), "at-the-root", "{}");
    transcript(escape(workspace) + "-src-main", "in-a-subdirectory", "{}");

    assertThat(registry().current())
        .extracting(SessionRegistry.Entry::id)
        .containsExactlyInAnyOrder("at-the-root", "in-a-subdirectory");
  }

  @Test
  @DisplayName("does not pick up a sibling directory that merely shares a prefix")
  void doesNotMatchSibling() throws IOException {
    // The bug this exists for: watching /Users/me/Dev would otherwise pull in every session from
    // /Users/me/Dev2, which is a sibling and not a child. Same class as the lsof prefix case.
    transcript(escape(workspace), "ours", "{}");
    transcript(escape(workspace) + "2", "someone-elses", "{}");

    assertThat(registry().current()).extracting(SessionRegistry.Entry::id).containsExactly("ours");
  }

  @Test
  @DisplayName("reads the title Claude Code wrote before the watcher started")
  void readsTitleFromExistingTranscript() throws IOException {
    // The tail skips whatever a transcript already held, and the title is written near the start of
    // a session - so without searching the file every older session stays an opaque identifier.
    transcript(
        escape(workspace),
        "session-a",
        "{\"type\":\"ai-title\",\"sessionId\":\"session-a\",\"aiTitle\":\"Refactor the parser\"}");

    assertThat(registry().current())
        .singleElement()
        .satisfies(e -> assertThat(e.title()).isEqualTo("Refactor the parser"));
  }

  @Test
  @DisplayName("a session with no title is listed rather than hidden")
  void listsUntitledSessions() throws IOException {
    transcript(escape(workspace), "session-a", "{}");

    assertThat(registry().current()).singleElement().satisfies(e -> assertThat(e.title()).isNull());
  }

  @Test
  @DisplayName("keeps only the most recent sessions, newest first")
  void boundsAndOrdersSessions() throws IOException {
    // A real project here has 333 sessions, which is not a list anyone picks from.
    props.setMaxSessions(3);
    for (int i = 0; i < 6; i++) {
      Path file = transcript(escape(workspace), "session-" + i, "{}");
      Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L + i * 1000L));
    }

    assertThat(registry().current())
        .extracting(SessionRegistry.Entry::id)
        .containsExactly("session-5", "session-4", "session-3");
  }

  @Test
  @DisplayName("a transcript written long ago is not live")
  void marksStaleSessionsAsNotLive() throws IOException {
    Path file = transcript(escape(workspace), "session-a", "{}");
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

    assertThat(registry().current()).singleElement().satisfies(e -> assertThat(e.live()).isFalse());
  }

  @Test
  @DisplayName("nothing is listed while no workspace is being watched")
  void emptyWithoutWorkspace() throws IOException {
    transcript(escape(workspace), "session-a", "{}");
    props.setWorkspace("");

    assertThat(registry().current()).isEmpty();
  }
}
