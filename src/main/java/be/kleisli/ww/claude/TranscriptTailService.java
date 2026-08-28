package be.kleisli.ww.claude;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.Text;
import be.kleisli.ww.core.WatchEvent;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Layer 1a: reads Claude Code's own session transcripts.
 *
 * <p>Claude Code appends every turn as one JSON object per line to {@code
 * ~/.claude/projects/<escaped-cwd>/<session-id>.jsonl}. That file already contains exactly what an
 * observer wants — every tool call with its arguments, every result, the session id and the working
 * directory — with attribution that is correct by construction. No OS tracing, no entitlements, no
 * race with a writer that has already closed its file descriptor.
 *
 * <p>The CLI is never touched: this only reads files it writes anyway.
 */
@Service
public class TranscriptTailService {

  private static final Logger log = LoggerFactory.getLogger(TranscriptTailService.class);
  private static final int SUMMARY_LIMIT = 240;

  private final ActiveWorkspace active;
  private final TranscriptLocator locator;
  private final SessionRegistry sessions;
  private final EventBus bus;
  private final ObjectMapper mapper;

  /** Byte offset consumed so far, per transcript file. */
  private final Map<Path, Long> offsets = new HashMap<>();

  /** tool_use_id -> short label, so a result can be tied back to the call that produced it. */
  private final Map<String, String> pendingCalls =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > 500;
        }
      };

  public TranscriptTailService(
      ActiveWorkspace active,
      TranscriptLocator locator,
      SessionRegistry sessions,
      EventBus bus,
      ObjectMapper mapper) {
    this.active = active;
    this.locator = locator;
    this.sessions = sessions;
    this.bus = bus;
    this.mapper = mapper;
  }

  @Scheduled(fixedDelayString = "${watcher.transcript-poll-ms:500}")
  public void poll() {
    for (Path file : locator.transcripts()) {
      tail(file);
    }
  }

  private void tail(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      Long known = offsets.get(file);
      if (known == null) {
        // A transcript that already exists when we start is history, not activity.
        // Replaying it would drown the live session in noise.
        offsets.put(file, length);
        return;
      }
      if (length < known) {
        // Truncated or rotated: resync rather than read garbage.
        offsets.put(file, length);
        return;
      }
      if (length == known) {
        return;
      }

      raf.seek(known);
      byte[] chunk = new byte[(int) Math.min(length - known, 8L * 1024 * 1024)];
      raf.readFully(chunk);

      int lastNewline = -1;
      for (int i = chunk.length - 1; i >= 0; i--) {
        if (chunk[i] == '\n') {
          lastNewline = i;
          break;
        }
      }
      if (lastNewline < 0) {
        // Partial line only; wait for the writer to finish it.
        return;
      }
      offsets.put(file, known + lastNewline + 1);

      String text = new String(chunk, 0, lastNewline + 1, StandardCharsets.UTF_8);
      for (String line : text.split("\n")) {
        if (!line.isBlank()) {
          handle(line);
        }
      }
    } catch (IOException e) {
      log.debug("cannot tail {}: {}", file, e.toString());
    }
  }

  private void handle(String line) {
    JsonNode root;
    try {
      root = mapper.readTree(line);
    } catch (RuntimeException e) {
      // A half-written line will parse fine on the next poll; dropping it is correct.
      return;
    }
    String session = root.path("sessionId").asString(null);

    // Claude Code writes a generated title for the session into the transcript. Picking it up as
    // it goes past turns an opaque session id into something selectable in the UI.
    if ("ai-title".equals(root.path("type").asString(""))) {
      sessions.recordTitle(session, root.path("aiTitle").asString(null));
      return;
    }

    JsonNode content = root.path("message").path("content");
    if (!content.isArray()) {
      return;
    }
    for (JsonNode block : content) {
      switch (block.path("type").asString()) {
        case "tool_use" -> emitToolUse(block, session);
        case "tool_result" -> emitToolResult(block, session);
        default -> {
          /* thinking and text blocks are narration, not actions */
        }
      }
    }
  }

  private void emitToolUse(JsonNode block, String session) {
    String tool = block.path("name").asString("tool");
    JsonNode input = block.path("input");
    String label = describe(tool, input);
    pendingCalls.put(block.path("id").asString(""), label);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tool", tool);
    detail.put("input", Text.truncate(input.toString(), Text.DETAIL_LIMIT));

    bus.publish(
        WatchEvent.of(WatchEvent.Source.TRANSCRIPT, "TOOL_USE")
            .agent("claude-code")
            .session(session)
            .summary(label)
            .path(relativeFilePath(input))
            .mcpServer(mcpServer(tool))
            .subagent(subagent(tool, input))
            .detail(detail));
  }

  private void emitToolResult(JsonNode block, String session) {
    String label = pendingCalls.remove(block.path("tool_use_id").asString(""));
    boolean error = block.path("is_error").asBoolean(false);
    JsonNode content = block.path("content");
    String body = content.isString() ? content.asString() : content.toString();

    bus.publish(
        WatchEvent.of(WatchEvent.Source.TRANSCRIPT, error ? "TOOL_ERROR" : "TOOL_RESULT")
            .agent("claude-code")
            .session(session)
            .summary(label != null ? label : firstLine(body))
            .detail("output", Text.truncate(body, Text.DETAIL_LIMIT)));
  }

  /** A short, human-readable line for the activity feed. */
  private String describe(String tool, JsonNode input) {
    String value =
        switch (tool) {
          case "Bash", "BashOutput" -> "$ " + input.path("command").asString("");
          case "Read", "Write", "Edit", "NotebookEdit" -> input.path("file_path").asString("");
          case "Glob", "Grep" -> input.path("pattern").asString("");
          case "Skill" -> input.path("skill").asString("");
          case "Task", "Agent" -> input.path("description").asString("");
          case "WebFetch", "WebSearch" ->
              input.path("url").asString(input.path("query").asString(""));
          default -> "";
        };
    // For an MCP call the raw mcp__server__tool is mostly punctuation, and the server is already
    // carried as its own field - so the summary is just the tool.
    if (tool.startsWith("mcp__")) {
      return mcpTail(tool);
    }
    if (value.isBlank()) {
      return tool;
    }
    String firstLine = value.lines().findFirst().orElse(value);
    return tool + "  " + Text.truncate(firstLine, SUMMARY_LIMIT);
  }

  /** The tool half of an mcp__server__tool name. */
  private static String mcpTail(String tool) {
    String[] parts = tool.split("__", 3);
    return parts.length > 2 ? parts[2] : tool;
  }

  /**
   * The MCP server a call went to, when it went to one.
   *
   * <p>Tool names are {@code mcp__<server>__<tool>}, so the server is there for the taking. Worth
   * taking: across the transcripts on this machine MCP calls run into the hundreds, and until now a
   * call to Jenkins looked exactly like a call to the filesystem.
   */
  private static String mcpServer(String tool) {
    if (!tool.startsWith("mcp__")) {
      return null;
    }
    String[] parts = tool.split("__", 3);
    return parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
  }

  /**
   * The kind of subagent a call launched, when it launched one.
   *
   * <p>Not taken from {@code isSidechain}, which sounds like the right field and is never set in
   * these transcripts - measured across sixty of them. The launch itself is recorded, and that is
   * what can honestly be shown.
   */
  private static String subagent(String tool, JsonNode input) {
    if (!tool.equals("Task") && !tool.equals("Agent")) {
      return null;
    }
    String kind = input.path("subagent_type").asString(null);
    return kind == null || kind.isBlank() ? "agent" : kind;
  }

  /** File-touching tools get a workspace-relative path so the UI can link them to a diff. */
  private String relativeFilePath(JsonNode input) {
    String raw = input.path("file_path").asString("");
    if (raw.isBlank()) {
      return null;
    }
    Path workspace = active.get();
    Path path = Path.of(raw).toAbsolutePath().normalize();
    return workspace != null && path.startsWith(workspace)
        ? workspace.relativize(path).toString()
        : raw;
  }

  /**
   * A usable summary for a result whose call we never saw.
   *
   * <p>Happens when the watcher starts mid-session: the tool_use was already history by the time we
   * began tailing. Saying "result" tells nobody anything, and it is the line a notification quotes.
   */
  private static String firstLine(String body) {
    if (body == null || body.isBlank()) {
      return "result";
    }
    return Text.truncate(body.strip().lines().findFirst().orElse("result"), 120);
  }

  /** Exposed for the status endpoint so the UI can say whether layer 1 is actually live. */
  public List<String> watchedTranscripts() {
    return locator.directories().stream().map(Path::toString).toList();
  }
}
