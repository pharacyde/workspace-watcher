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

  /**
   * agentId -> kind of subagent, learned from that agent's own turns.
   *
   * <p>Access-ordered, so an agent that is still working stays in the map. Insertion order would
   * evict a long-running agent once 500 others had started, after which its tool results - which
   * carry the id but not the kind - would fall back to a generic label and split away from its own
   * calls.
   */
  private final Map<String, String> agentKinds =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > 500;
        }
      };

  /**
   * The workspace the offsets below were established for, and whether that has happened yet.
   *
   * <p>The first poll after start, and the first after a workspace switch, is a baseline: every
   * transcript it finds already existed and is history. After that, a file we have not seen before
   * did not exist at the previous poll, so it was created while we were watching and every line in
   * it is ours to report.
   *
   * <p>This replaced a comparison against the file's creation time, which macOS reports only to the
   * second - so a transcript created in the same second the watcher started was indistinguishable
   * from one that had been there for an hour. Whether we saw the file last time needs no clock.
   */
  private Path baselineWorkspace;

  private boolean baselineTaken;

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
    Path workspace = active.get();
    // A switch makes every transcript of the new project unknown to us at once. Without this they
    // would all count as new and the feed would be buried under a replay of the whole project.
    boolean baseline = !baselineTaken || !java.util.Objects.equals(workspace, baselineWorkspace);
    if (baseline) {
      offsets.clear();
      baselineWorkspace = workspace;
      baselineTaken = true;
    }
    for (Path file : locator.tailable()) {
      tail(file, baseline);
    }
  }

  private void tail(Path file, boolean baseline) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      Long known = offsets.get(file);
      if (known == null) {
        // A transcript that was already there on the baseline poll is history, not activity:
        // replaying a finished 24 MB session would bury the live one. A file that appears later is
        // the opposite - a session or a subagent that began while we were watching. That matters
        // most for subagents, whose directory does not exist until a session first delegates, so
        // by the time we can see the file it already has lines in it. Skipping those would lose
        // what the agent did in its first seconds permanently, rather than reporting it late.
        offsets.put(file, baseline ? length : 0L);
        if (baseline) {
          return;
        }
        known = 0L;
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
    Origin origin = new Origin(session, insideSubagent(root), root.path("agentId").asString(null));

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
        case "tool_use" -> emitToolUse(block, origin);
        case "tool_result" -> emitToolResult(block, origin);
        default -> {
          /* thinking and text blocks are narration, not actions */
        }
      }
    }
  }

  /**
   * Where a record came from: always a session, and when it was written from inside a subagent also
   * the kind of agent and the identity of that particular one.
   */
  private record Origin(String session, String subagent, String agentId) {}

  private void emitToolUse(JsonNode block, Origin origin) {
    String tool = block.path("name").asString("tool");
    JsonNode input = block.path("input");
    String label = describe(tool, input);
    pendingCalls.put(block.path("id").asString(""), label);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tool", tool);
    detail.put("input", Text.truncate(input.toString(), Text.DETAIL_LIMIT));
    if (origin.agentId() != null) {
      detail.put("agentId", origin.agentId());
    }

    bus.publish(
        WatchEvent.of(WatchEvent.Source.TRANSCRIPT, "TOOL_USE")
            .agent("claude-code")
            .session(origin.session())
            .summary(label)
            .path(relativeFilePath(input))
            .mcpServer(mcpServer(tool))
            .subagent(origin.subagent() != null ? origin.subagent() : subagent(tool, input))
            .detail(detail));
  }

  private void emitToolResult(JsonNode block, Origin origin) {
    String label = pendingCalls.remove(block.path("tool_use_id").asString(""));
    boolean error = block.path("is_error").asBoolean(false);
    JsonNode content = block.path("content");
    String body = content.isString() ? content.asString() : content.toString();

    bus.publish(
        WatchEvent.of(WatchEvent.Source.TRANSCRIPT, error ? "TOOL_ERROR" : "TOOL_RESULT")
            .agent("claude-code")
            .session(origin.session())
            .summary(label != null ? label : firstLine(body))
            .subagent(origin.subagent())
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
   * The kind of agent a record was written by, when it was written from inside a subagent.
   *
   * <p>{@code isSidechain} marks the record as a subagent's own work and {@code attributionAgent}
   * names the kind - {@code general-purpose}, {@code Explore}, {@code fork} and so on. Both are
   * set, contrary to what an earlier reading of these transcripts concluded: that reading only ever
   * looked at session transcripts, where a subagent's records do not appear at all.
   */
  private String insideSubagent(JsonNode root) {
    if (!root.path("isSidechain").asBoolean(false)) {
      return null;
    }
    String agentId = root.path("agentId").asString(null);
    // A subagent started by a skill is named by the skill instead - "code-review",
    // "superpowers:writing-plans". Thousands of records on this machine, and the name is right
    // there; falling through to a generic label would throw away attribution we actually have.
    String kind =
        root.path("attributionAgent").asString(root.path("attributionSkill").asString(null));
    if (kind != null && !kind.isBlank()) {
      if (agentId != null) {
        agentKinds.put(agentId, kind);
      }
      return kind;
    }
    // Only the agent's own turns name the kind; the tool results coming back to it carry just the
    // id. Looking the kind up keeps a call and its result in one lane instead of splitting them.
    String remembered = agentId == null ? null : agentKinds.get(agentId);
    return remembered != null ? remembered : "agent";
  }

  /**
   * The kind of subagent a call launched, when it launched one.
   *
   * <p>Complementary to {@link #insideSubagent}: this tags the {@code Task} call that starts an
   * agent, that tags the work the agent then does. Both land in the same field, so filtering on a
   * kind shows the delegation and its consequences together.
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
