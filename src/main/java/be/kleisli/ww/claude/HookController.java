package be.kleisli.ww.claude;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;

/**
 * Layer 1b: the push half of agent attribution.
 *
 * <p>Claude Code hooks (and the equivalent in other agents) POST here at the moment an action
 * happens, so the feed does not have to wait for the transcript to be flushed. The payload shape
 * is treated as advisory: anything unrecognised is still recorded, just with a generic label.
 *
 * <p>This endpoint is unauthenticated by design and the app binds to loopback by default. Do not
 * expose it; reach a remote instance through an SSH tunnel or a private overlay network.
 */
@RestController
@RequestMapping("/api/hook")
public class HookController {

    private final EventBus bus;

    public HookController(EventBus bus) {
        this.bus = bus;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody JsonNode payload) {
        String hookName = firstNonBlank(payload, "hook_event_name", "hookEventName");
        String tool = firstNonBlank(payload, "tool_name", "toolName");
        String session = firstNonBlank(payload, "session_id", "sessionId");
        String agent = firstNonBlank(payload, "agent");

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("payload", payload);

        String summary = tool != null ? tool : (hookName != null ? hookName : "hook");
        JsonNode input = payload.path("tool_input");
        String path = input.path("file_path").asString(null);
        if (input.hasNonNull("command")) {
            summary = summary + "  $ " + input.path("command").asString().lines().findFirst().orElse("");
        } else if (path != null) {
            summary = summary + "  " + path;
        }

        bus.publish(WatchEvent.of(WatchEvent.Source.HOOK, hookName == null ? "HOOK" : hookName)
                .agent(agent == null ? "claude-code" : agent)
                .session(session)
                .summary(summary)
                .path(path)
                .detail(detail));

        // Hooks block the agent until they return, so answer immediately and say nothing that
        // could alter its behaviour.
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
