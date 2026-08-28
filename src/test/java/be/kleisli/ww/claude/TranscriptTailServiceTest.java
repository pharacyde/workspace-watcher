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
  private Path transcript;
  private EventBus bus;
  private TranscriptTailService service;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    Path claudeHome = Files.createDirectory(tmp.resolve("claude"));
    Path projects =
        Files.createDirectories(
            claudeHome.resolve("projects").resolve(TranscriptLocator.escapeCwd(workspace)));
    transcript = projects.resolve("session.jsonl");
    Files.writeString(transcript, "");

    WatcherProperties props = new WatcherProperties();
    props.setClaudeHome(claudeHome.toString());
    props.setWorkspace(workspace.toString());
    bus = new EventBus(props);
    ActiveWorkspace active = new ActiveWorkspace(props);
    TranscriptLocator locator = new TranscriptLocator(props, active);
    service =
        new TranscriptTailService(
            active,
            locator,
            new SessionRegistry(locator, new ObjectMapper()),
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
}
