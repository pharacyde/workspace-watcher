package be.kleisli.ww.claude;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.Text;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.guard.SensitiveContentScanner;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a raw agent hook payload into an event.
 *
 * <p>Shared by both delivery paths — the spool directory and the GraphQL mutation — so a hook is
 * interpreted identically however it arrived.
 *
 * <p>Secrets are replaced here, before the event exists: nothing downstream - the ring buffer, the
 * archive, a subscriber - ever sees the token. Measured cost in docs/collectors.md, "Guard".
 */
public final class HookEvents {

  private HookEvents() {}

  public static void publish(
      EventBus bus,
      ObjectMapper mapper,
      SensitiveContentScanner scanner,
      String rawJson,
      String via) {
    JsonNode payload;
    try {
      payload = mapper.readTree(rawJson);
    } catch (RuntimeException e) {
      // Recorded rather than dropped: a malformed hook is itself worth seeing. The message may
      // quote the input, so it goes through the scanner like the payload would have.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.HOOK, "HOOK")
              .summary("unparseable hook payload (" + via + ")")
              .detail("error", scanner.redact(e.toString()).text()));
      return;
    }

    String hookName = text(payload, "hook_event_name", "hookEventName");
    String tool = text(payload, "tool_name", "toolName");
    String session = text(payload, "session_id", "sessionId");
    String agent = text(payload, "agent");

    JsonNode input = payload.path("tool_input");
    String path = input.path("file_path").asString(null);
    String summary = tool != null ? tool : (hookName != null ? hookName : "hook");
    if (input.hasNonNull("command")) {
      summary += "  $ " + input.path("command").asString("").lines().findFirst().orElse("");
    } else if (path != null) {
      summary += "  " + path;
    }

    // A bounded rendering, not the parsed tree: a tool_response from a large file read can be
    // megabytes, and thousands of those would sit in the ring buffer forever.
    SensitiveContentScanner.Redacted redacted = scanner.redactHead(rawJson, Text.DETAIL_LIMIT);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("via", via);
    detail.put("payload", Text.truncate(redacted.text()));
    if (!redacted.rules().isEmpty()) {
      detail.put("sensitive", redacted.rules());
    }

    bus.publish(
        WatchEvent.of(WatchEvent.Source.HOOK, hookName == null ? "HOOK" : hookName)
            .agent(agent == null ? "claude-code" : agent)
            .session(session)
            .summary(scanner.redact(summary).text())
            .path(path)
            .detail(detail));
  }

  private static String text(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = node.path(field).asString(null);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
