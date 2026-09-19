package be.kleisli.ww.claude;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.Text;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.guard.SensitiveContentScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HookEventsTest {

  private EventBus bus;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    bus = new EventBus(new WatcherProperties());
  }

  private WatchEvent publish(String payload) {
    HookEvents.publish(bus, mapper, new SensitiveContentScanner(), payload, "spool");
    return bus.replay().getFirst();
  }

  @Test
  @DisplayName("summarises a command hook with the command itself")
  void summarisesCommand() {
    WatchEvent event =
        publish(
            """
            {"hook_event_name":"PostToolUse","tool_name":"Bash","session_id":"s1",
             "tool_input":{"command":"mvn test\\nsecond line"}}\
            """);

    assertThat(event.source()).isEqualTo(WatchEvent.Source.HOOK);
    assertThat(event.type()).isEqualTo("PostToolUse");
    assertThat(event.sessionId()).isEqualTo("s1");
    assertThat(event.agent()).isEqualTo("claude-code");
    // Only the first line: a heredoc would otherwise take over the feed.
    assertThat(event.summary()).isEqualTo("Bash  $ mvn test");
  }

  @Test
  @DisplayName("carries the file path of a file-touching hook")
  void summarisesFilePath() {
    WatchEvent event =
        publish(
            """
            {"hook_event_name":"PreToolUse","tool_name":"Edit",
             "tool_input":{"file_path":"/repo/src/App.java"}}\
            """);

    assertThat(event.path()).isEqualTo("/repo/src/App.java");
    assertThat(event.summary()).isEqualTo("Edit  /repo/src/App.java");
  }

  @Test
  @DisplayName("records a malformed payload rather than dropping it")
  void recordsMalformedPayload() {
    WatchEvent event = publish("{not json");

    assertThat(event.source()).isEqualTo(WatchEvent.Source.HOOK);
    assertThat(event.summary()).contains("unparseable");
  }

  @Test
  @DisplayName("bounds what a huge tool_response can put in the buffer")
  void truncatesLargePayloads() {
    String payload =
        "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Read\",\"tool_response\":\""
            + "A".repeat(2_000_000)
            + "\"}";

    WatchEvent event = publish(payload);

    @SuppressWarnings("unchecked")
    var detail = (java.util.Map<String, Object>) event.detail();
    assertThat((String) detail.get("payload")).hasSizeLessThan(Text.DETAIL_LIMIT + 10);
  }

  @Test
  @DisplayName("a token in a hook payload is replaced before the event exists, summary included")
  void redactsSecretsAtRecordTime() {
    String token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij";
    WatchEvent event =
        publish(
            """
            {"hook_event_name":"PostToolUse","tool_name":"Bash",
             "tool_input":{"command":"git push https://x:%s@github.com/x/y && echo GH=%s"}}\
            """
                .formatted(token, token));

    @SuppressWarnings("unchecked")
    var detail = (java.util.Map<String, Object>) event.detail();
    assertThat(event.summary()).doesNotContain(token).contains("‹github-token:ghp_…›");
    assertThat((String) detail.get("payload"))
        .doesNotContain(token)
        .contains("‹github-token:ghp_…›");
    assertThat(detail.get("sensitive")).isEqualTo(java.util.List.of("github-token"));
  }

  @Test
  @DisplayName("falls back to the hook name when there is no tool")
  void handlesToollessHook() {
    assertThat(publish("{\"hook_event_name\":\"SessionStart\"}").summary())
        .isEqualTo("SessionStart");
  }
}
