package be.kleisli.ww.claude;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.Text;
import be.kleisli.ww.core.WatchEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a raw agent hook payload into an event.
 *
 * <p>Shared by both delivery paths — the spool directory and the GraphQL mutation — so a hook is
 * interpreted identically however it arrived.
 */
public final class HookEvents {

  private HookEvents() {}

  public static void publish(EventBus bus, ObjectMapper mapper, String rawJson, String via) {
    JsonNode payload;
    try {
      payload = mapper.readTree(rawJson);
    } catch (RuntimeException e) {
      // Recorded rather than dropped: a malformed hook is itself worth seeing.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.HOOK, "HOOK")
              .summary("unparseable hook payload (" + via + ")")
              .detail("error", e.toString()));
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
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("via", via);
    detail.put("payload", Text.truncate(rawJson));

    bus.publish(
        WatchEvent.of(WatchEvent.Source.HOOK, hookName == null ? "HOOK" : hookName)
            .agent(agent == null ? "claude-code" : agent)
            .session(session)
            .summary(summary)
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
