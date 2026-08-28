package be.kleisli.ww.claude;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TranscriptTailServiceTest {

  @TempDir Path tmp;

  private Path workspace;
  private Path projects;
  private Path transcript;
  private WatcherProperties props;
  private ActiveWorkspace active;
  private EventBus bus;
  private TranscriptTailService service;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    Path claudeHome = Files.createDirectory(tmp.resolve("claude"));
    projects =
        Files.createDirectories(
            claudeHome.resolve("projects").resolve(TranscriptLocator.escapeCwd(workspace)));
    transcript = projects.resolve("session.jsonl");
    Files.writeString(transcript, "");

    props = new WatcherProperties();
    props.setClaudeHome(claudeHome.toString());
    props.setWorkspace(workspace.toString());
    bus = new EventBus(props);
    active = new ActiveWorkspace(props);
    TranscriptLocator locator = new TranscriptLocator(props, active);
    service =
        new TranscriptTailService(
            active,
            locator,
            new SessionRegistry(locator, props, new ObjectMapper()),
            bus,
            new ObjectMapper());
  }

  private void append(String line) throws IOException {
    Files.writeString(
        transcript, line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
  }

  private List<WatchEvent> poll() {
    service.poll();
    return bus.replay();
  }

  @Test
  @DisplayName("maps a working directory to Claude Code's transcript directory name")
  void escapesWorkingDirectory() {
    assertThat(TranscriptLocator.escapeCwd(Path.of("/Users/x/Dev/my.app_1")))
        .isEqualTo("-Users-x-Dev-my-app-1");
  }

  @Test
  @DisplayName("treats a transcript that already exists as history, not activity")
  void skipsExistingContent() throws IOException {
    append(toolUse("Bash", "{\"command\":\"echo old\"}"));

    assertThat(poll()).isEmpty();

    append(toolUse("Bash", "{\"command\":\"echo new\"}"));
    assertThat(poll())
        .singleElement()
        .extracting(WatchEvent::summary)
        .asString()
        .contains("echo new");
  }

  @Test
  @DisplayName("waits for a half-written line instead of dropping or corrupting it")
  void handlesPartialLines() throws IOException {
    poll();
    String line = toolUse("Bash", "{\"command\":\"echo split\"}");
    Files.writeString(
        transcript,
        line.substring(0, 30),
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    assertThat(poll()).isEmpty();

    Files.writeString(
        transcript,
        line.substring(30) + "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertThat(poll())
        .singleElement()
        .extracting(WatchEvent::summary)
        .asString()
        .contains("echo split");
  }

  @Test
  @DisplayName("decodes multi-byte characters split across a read")
  void handlesUtf8AcrossReads() throws IOException {
    poll();
    append(toolUse("Bash", "{\"command\":\"echo dræben — ünïcode\"}"));

    assertThat(poll())
        .singleElement()
        .extracting(WatchEvent::summary)
        .asString()
        .contains("dræben — ünïcode");
  }

  @Test
  @DisplayName("reports a file-touching tool with a workspace-relative path")
  void relativisesFilePaths() throws IOException {
    poll();
    append(toolUse("Edit", "{\"file_path\":\"" + workspace.resolve("src/A.java") + "\"}"));

    WatchEvent event = poll().getFirst();
    assertThat(event.source()).isEqualTo(WatchEvent.Source.TRANSCRIPT);
    assertThat(event.agent()).isEqualTo("claude-code");
    assertThat(event.path()).isEqualTo("src/A.java");
  }

  @Test
  @DisplayName("names the MCP server a call went to")
  void labelsMcpCalls() throws IOException {
    // Without this a call to Jenkins reads exactly like editing a file two lines above it.
    poll();
    append(toolUse("mcp__jenkins__jenkins_get_build_status", "{\"job\":\"omv-master\"}"));

    WatchEvent event = poll().getFirst();
    assertThat(event.mcpServer()).isEqualTo("jenkins");
    // The server is its own field, so the summary carries only the tool - mcp__a__b is mostly
    // punctuation.
    assertThat(event.summary()).isEqualTo("jenkins_get_build_status");
  }

  @Test
  @DisplayName("names the kind of subagent a call launched")
  void labelsSubagentLaunches() throws IOException {
    poll();
    append(
        toolUse("Task", "{\"description\":\"Find the flaky test\",\"subagent_type\":\"Explore\"}"));

    WatchEvent event = poll().getFirst();
    assertThat(event.subagent()).isEqualTo("Explore");
    assertThat(event.summary()).contains("Find the flaky test");
  }

  @Test
  @DisplayName("an ordinary tool call carries neither label")
  void leavesOrdinaryCallsUnlabelled() throws IOException {
    poll();
    append(toolUse("Bash", "{\"command\":\"mvn verify\"}"));

    WatchEvent event = poll().getFirst();
    assertThat(event.mcpServer()).isNull();
    assertThat(event.subagent()).isNull();
  }

  @Test
  @DisplayName("resyncs rather than reading garbage when a transcript is truncated")
  void resyncsOnTruncation() throws IOException {
    poll();
    append(toolUse("Bash", "{\"command\":\"echo one\"}"));
    assertThat(poll()).hasSize(1);

    Files.writeString(transcript, "");
    assertThat(poll()).hasSize(1);

    append(toolUse("Bash", "{\"command\":\"echo two\"}"));
    assertThat(poll()).hasSize(2);
  }

  private static String toolUse(String tool, String input) {
    return """
    {"type":"assistant","sessionId":"s1","message":{"content":[\
    {"type":"tool_use","id":"t1","name":"%s","input":%s}]}}\
    """
        .formatted(tool, input);
  }

  /**
   * A subagent transcript, one directory level below the session it belongs to. Claude Code writes
   * these to {@code <session-id>/subagents/agent-<id>.jsonl}.
   */
  private Path subagentTranscript(String session, String agentId) throws IOException {
    Path dir = Files.createDirectories(projects.resolve(session).resolve("subagents"));
    Path file = dir.resolve("agent-" + agentId + ".jsonl");
    Files.writeString(file, "");
    return file;
  }

  private static void appendTo(Path file, String line) throws IOException {
    Files.writeString(
        file, line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
  }

  @Test
  @DisplayName("follows the work a subagent does in its own transcript")
  void readsSubagentTranscripts() throws IOException {
    Path agent = subagentTranscript("session", "abc123");
    assertThat(poll()).isEmpty();

    appendTo(agent, sidechainToolUse("Bash", "{\"command\":\"echo from a subagent\"}"));

    assertThat(poll())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.summary()).contains("echo from a subagent");
              // The parent's session, so the subagent appears under it rather than beside it.
              assertThat(e.sessionId()).isEqualTo("s1");
              assertThat(e.subagent()).isEqualTo("Explore");
            });
  }

  @Test
  @DisplayName("tags a subagent's results as well as its calls, so the pair stays together")
  void tagsSubagentResults() throws IOException {
    Path agent = subagentTranscript("session", "abc123");
    poll();
    appendTo(agent, sidechainToolUse("Bash", "{}"));
    poll();

    appendTo(
        agent,
"""
{"type":"user","sessionId":"s1","isSidechain":true,"attributionAgent":"Explore",\
"agentId":"abc123","message":{"content":[\
{"type":"tool_result","tool_use_id":"t9","content":"done"}]}}\
""");

    // Named by the call that came before it: a tool result carries the agent id but not the kind.
    assertThat(poll())
        .last()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo("TOOL_RESULT");
              assertThat(e.subagent()).isEqualTo("Explore");
            });
  }

  @Test
  @DisplayName("does not mistake a main-session record for subagent work")
  void mainSessionIsNotTaggedAsSubagent() throws IOException {
    poll();
    append(toolUse("Read", "{\"file_path\":\"/x/y.txt\"}"));

    assertThat(poll()).singleElement().satisfies(e -> assertThat(e.subagent()).isNull());
  }

  @Test
  @DisplayName("a subagent transcript is not a session of its own")
  void subagentIsNotASession() throws IOException {
    subagentTranscript("session", "abc123");

    TranscriptLocator locator = new TranscriptLocator(props, active);
    assertThat(locator.transcripts()).containsExactly(transcript);
    assertThat(locator.allTranscripts()).hasSize(2);
  }

  private static String sidechainToolUse(String tool, String input) {
    return
"""
{"type":"assistant","sessionId":"s1","isSidechain":true,"attributionAgent":"Explore",\
"agentId":"abc123","message":{"content":[\
{"type":"tool_use","id":"t1","name":"%s","input":%s}]}}\
"""
        .formatted(tool, input);
  }

  @Test
  @DisplayName("names a skill-started subagent by its skill")
  void namesSkillStartedSubagents() throws IOException {
    Path agent = subagentTranscript("session", "skill1");
    poll();
    appendTo(
        agent,
"""
{"type":"assistant","sessionId":"s1","isSidechain":true,"attributionSkill":"code-review",\
"agentId":"skill1","message":{"content":[\
{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/a/b.txt"}}]}}\
""");

    // Thousands of records on this machine carry attributionSkill and no attributionAgent; falling
    // through to a generic label would throw away a name that is right there.
    assertThat(poll()).last().satisfies(e -> assertThat(e.subagent()).isEqualTo("code-review"));
  }

  @Test
  @DisplayName("the tail skips a finished subagent while the cost still counts it")
  void tailAndCostUseDifferentWindows() throws IOException {
    Path agent = subagentTranscript("session", "old1");
    Files.setLastModifiedTime(
        agent, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(86_400)));

    TranscriptLocator locator = new TranscriptLocator(props, active);
    // Subagent transcripts are never cleaned up, so the tail bounds what it opens by recency. What
    // was spent does not go stale, so the same bound would make the total wrong.
    assertThat(locator.allTranscripts()).containsExactly(transcript);
    assertThat(locator.everyTranscript()).containsExactlyInAnyOrder(transcript, agent);
  }
}
