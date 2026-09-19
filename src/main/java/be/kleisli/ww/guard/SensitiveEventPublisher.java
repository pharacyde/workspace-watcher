package be.kleisli.ww.guard;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Says, as an event, when something an agent sent or received looked like a secret or personal data
 * (P18-01).
 *
 * <p>A bus subscriber beside {@link be.kleisli.ww.store.EventStore}: it runs on the collector's
 * thread, so it is kept to one scan per event and swallows every failure (invariant 1). One event
 * per rule that fired, {@code SENSITIVE_OUTBOUND} when the call it looked at names a remote target
 * and {@code SENSITIVE_CONTENT} otherwise; attribution is copied from the source event, never
 * derived (invariant 2). The excerpt is the four characters before the match and never the match.
 */
@Service
public class SensitiveEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(SensitiveEventPublisher.class);
  private static final String TYPE_PREFIX = "SENSITIVE_";
  private static final int EXCERPT = 4;

  private final EventBus bus;
  private final SensitiveContentScanner scanner;
  private final ObjectMapper mapper;
  private final AtomicLong hits = new AtomicLong();
  private Runnable unsubscribe;

  public SensitiveEventPublisher(
      EventBus bus, SensitiveContentScanner scanner, ObjectMapper mapper) {
    this.bus = bus;
    this.scanner = scanner;
    this.mapper = mapper;
  }

  @PostConstruct
  void start() {
    unsubscribe = bus.subscribe(this::inspect);
  }

  @PreDestroy
  void stop() {
    if (unsubscribe != null) {
      unsubscribe.run();
    }
  }

  /** Events published since start, one per rule per source event. */
  public long hits() {
    return hits.get();
  }

  /** The tool a source event is about, and its input, when the event carried them. */
  private record ToolCall(String tool, JsonNode input) {
    static final ToolCall NONE = new ToolCall(null, null);
  }

  void inspect(WatchEvent event) {
    try {
      if (!worthScanning(event)) {
        return;
      }
      String text = textOf(event);
      List<SensitiveContentScanner.Hit> found = new ArrayList<>(scanner.scan(text));
      found.addAll(markers(text));
      if (found.isEmpty()) {
        return;
      }
      ToolCall call = toolCall(event);
      RemoteTarget target = RemoteTarget.of(call.tool(), call.input());
      for (var perRule : byRule(found).entrySet()) {
        publish(event, call, target, perRule.getKey(), perRule.getValue(), found, text);
      }
    } catch (RuntimeException e) {
      // The scanner runs on a collector's thread; a bug in it must cost an event, not a collector.
      log.debug("sensitive-content scan failed on seq={}: {}", event.seq(), e.toString());
    }
  }

  /**
   * Secrets never reach the bus: the producers redact them to {@code ‹rule:xxxx…›} first (P18-02),
   * so the marker is what this subscriber gets to see of them.
   */
  private static final Pattern MARKER =
      Pattern.compile("‹([a-z][a-z0-9-]{1,30}+):[^›…\\n]{0,12}+…›");

  private static List<SensitiveContentScanner.Hit> markers(String text) {
    List<SensitiveContentScanner.Hit> hits = new ArrayList<>();
    Matcher m = MARKER.matcher(text);
    while (m.find()) {
      hits.add(new SensitiveContentScanner.Hit(m.group(1), m.start(), m.end()));
    }
    return hits;
  }

  private static boolean worthScanning(WatchEvent event) {
    return switch (event.source()) {
      case HOOK, TRANSCRIPT -> true;
      case GUARD -> !event.type().startsWith(TYPE_PREFIX);
      case FS, SYSTEM -> false;
    };
  }

  /**
   * The summary and the string values of {@code detail}, not {@code detail} re-serialised: the
   * payload is already a JSON string and escaping it a second time would put a backslash before
   * every quote the rules look at.
   */
  private String textOf(WatchEvent event) {
    StringBuilder text = new StringBuilder(event.summary() == null ? "" : event.summary());
    Object detail = event.detail();
    if (detail instanceof Map<?, ?> map) {
      for (Object value : map.values()) {
        if (value != null) {
          text.append('\n').append(value);
        }
      }
    } else if (detail != null) {
      text.append('\n').append(detail instanceof String s ? s : mapper.writeValueAsString(detail));
    }
    return text.toString();
  }

  private ToolCall toolCall(WatchEvent event) {
    if (!(event.detail() instanceof Map<?, ?> detail)) {
      return ToolCall.NONE;
    }
    ToolCall call = ToolCall.NONE;
    if (detail.get("payload") instanceof String payload) {
      JsonNode fields = leniently(payload);
      call = new ToolCall(fields.path("tool_name").asString(null), fields.get("tool_input"));
    } else if (detail.get("tool") instanceof String tool) {
      call =
          new ToolCall(tool, detail.get("input") instanceof String input ? leniently(input) : null);
    }
    // A truncated payload can still name the tool but lose the command; the summary keeps its
    // first line, which is enough for the verb.
    if (call.tool() != null && call.input() == null) {
      String summary = event.summary() == null ? "" : event.summary();
      int at = summary.indexOf("  $ ");
      if (at >= 0) {
        call =
            new ToolCall(
                call.tool(), mapper.createObjectNode().put("command", summary.substring(at + 4)));
      }
    }
    return call;
  }

  /**
   * The top-level fields of an object that may have been cut off mid-way: {@code detail} holds
   * payloads truncated to a fixed size, and a PostToolUse with a large response is exactly the call
   * whose input matters. Reading stops at the first thing that does not parse.
   */
  private JsonNode leniently(String json) {
    var node = mapper.createObjectNode();
    try (JsonParser parser = mapper.createParser(json)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        return node;
      }
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = parser.currentName();
        parser.nextToken();
        node.set(name, parser.<JsonNode>readValueAsTree());
      }
    } catch (RuntimeException e) {
      // Whatever was read before the cut is what there is.
    }
    return node;
  }

  private static Map<String, List<SensitiveContentScanner.Hit>> byRule(
      List<SensitiveContentScanner.Hit> hits) {
    Map<String, List<SensitiveContentScanner.Hit>> grouped = new LinkedHashMap<>();
    for (SensitiveContentScanner.Hit hit : hits) {
      grouped.computeIfAbsent(hit.rule(), r -> new ArrayList<>()).add(hit);
    }
    return grouped;
  }

  private void publish(
      WatchEvent source,
      ToolCall call,
      RemoteTarget target,
      String rule,
      List<SensitiveContentScanner.Hit> ruleHits,
      List<SensitiveContentScanner.Hit> all,
      String text) {
    String where = call.tool() != null ? call.tool() : source.type();
    String summary = rule + " in " + where + (target.host() != null ? " → " + target.host() : "");

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("rule", rule);
    detail.put("sourceSeq", source.seq());
    detail.put("sourceType", source.type());
    detail.put("tool", call.tool());
    detail.put("host", target.host());
    detail.put("excerpt", excerpt(text, ruleHits.getFirst(), all));
    // Distinct values, not spans: a hook's summary repeats the payload's first line, so the same
    // token is found twice, and "2" would read as two secrets.
    detail.put(
        "count",
        (int) ruleHits.stream().map(h -> text.substring(h.start(), h.end())).distinct().count());

    hits.incrementAndGet();
    bus.publish(
        WatchEvent.of(
                WatchEvent.Source.GUARD,
                target.remote() ? "SENSITIVE_OUTBOUND" : "SENSITIVE_CONTENT")
            .summary(summary)
            .path(source.path())
            .pid(source.pid())
            .agent(source.agent())
            .session(source.sessionId())
            .mcpServer(source.mcpServer())
            .subagent(source.subagent())
            .detail(detail));
  }

  /**
   * The few characters before the match, so a reader can find it without being handed it. Clipped
   * at the end of any earlier hit: two tokens back to back would otherwise leak the tail of one as
   * the context of the next.
   */
  private static String excerpt(
      String text, SensitiveContentScanner.Hit hit, List<SensitiveContentScanner.Hit> all) {
    int start = Math.clamp(hit.start(), 0, text.length());
    int from = Math.max(0, start - EXCERPT);
    for (SensitiveContentScanner.Hit other : all) {
      if (other != hit && other.end() <= start) {
        from = Math.max(from, other.end());
      }
    }
    return text.substring(from, start).replace('\n', ' ') + "…";
  }
}
