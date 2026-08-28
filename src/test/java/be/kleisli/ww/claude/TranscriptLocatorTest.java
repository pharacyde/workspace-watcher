package be.kleisli.ww.claude;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptLocatorTest {

  @TempDir Path tmp;

  private Path projects;
  private TranscriptLocator locator;

  @BeforeEach
  void setUp() throws IOException {
    Path workspace = Files.createDirectory(tmp.resolve("project"));
    Path claudeHome = Files.createDirectory(tmp.resolve("claude"));
    projects =
        Files.createDirectories(
            claudeHome.resolve("projects").resolve(TranscriptLocator.escapeCwd(workspace)));

    WatcherProperties props = new WatcherProperties();
    props.setClaudeHome(claudeHome.toString());
    props.setWorkspace(workspace.toString());
    locator = new TranscriptLocator(props, new ActiveWorkspace(props));
  }

  private Path session(String id) throws IOException {
    Path file = projects.resolve(id + ".jsonl");
    Files.writeString(file, "");
    return file;
  }

  private Path subagent(String session, String agentId) throws IOException {
    Path dir = Files.createDirectories(projects.resolve(session).resolve("subagents"));
    Path file = dir.resolve("agent-" + agentId + ".jsonl");
    Files.writeString(file, "");
    return file;
  }

  @Test
  @DisplayName("finds a subagent directory created after the first look")
  void seesNewSubagentDirectories() throws IOException {
    session("s1");
    assertThat(locator.subagentTranscripts()).isEmpty();

    // subagents/ appears the moment a session first delegates, which is always after the listing
    // that did not find it. An earlier version held this listing for five seconds and lost
    // everything the agent did in that window.
    Path agent = subagent("s1", "a1");
    assertThat(locator.subagentTranscripts()).containsExactly(agent);
  }

  @Test
  @DisplayName("keeps a subagent transcript out of the session list")
  void subagentsAreNotSessions() throws IOException {
    Path session = session("s1");
    subagent("s1", "a1");

    assertThat(locator.transcripts()).containsExactly(session);
  }

  @Test
  @DisplayName("names the session a subagent transcript belongs to from its path")
  void readsSessionFromThePath() throws IOException {
    assertThat(TranscriptLocator.sessionOf(subagent("s1", "a1"))).isEqualTo("s1");
  }

  @Test
  @DisplayName("declines to name a session when the path cannot say")
  void sessionOfIsNullWithoutAGrandparent() {
    // Rather than returning something plausible: a wrong session id would put one agent's tokens on
    // another's bill, which is worse than an unattributed total.
    assertThat(TranscriptLocator.sessionOf(Path.of("agent-a1.jsonl"))).isNull();
  }

  @Test
  @DisplayName("only tails a workspace's own transcripts, not a sibling's")
  void doesNotMatchASiblingWorkspace() throws IOException {
    Path sibling = projects.getParent().resolve(projects.getFileName() + "2");
    Files.createDirectories(sibling);
    Files.writeString(sibling.resolve("other.jsonl"), "");

    assertThat(locator.forCosting()).noneMatch(p -> p.toString().contains("other.jsonl"));
  }
}
