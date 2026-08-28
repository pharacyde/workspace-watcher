package be.kleisli.ww.core;

import java.time.Instant;
import java.util.Map;

/**
 * One thing that happened in the workspace, at a point in time.
 *
 * <p>Strictly a chronicle. Current state - the git working tree, the process list - is broadcast
 * through {@link StateStream} instead, because republishing unchanged state into a chronological
 * feed is what turns a dashboard into noise.
 *
 * <p>{@code source} says how we know about it, and that is the whole point of the two-layer design:
 * {@code TRANSCRIPT} and {@code HOOK} events carry real attribution straight from the agent, while
 * {@code FS} events are the generic safety net and deliberately claim no actor.
 */
public record WatchEvent(
    long seq,
    Instant ts,
    Source source,
    String type,
    String summary,
    String path,
    Long pid,
    String agent,
    String sessionId,
    /** MCP server this call went to, when it did. Derived from the tool name. */
    String mcpServer,
    /** Kind of subagent this call launched, when it launched one. */
    String subagent,
    Object detail) {

  public enum Source {
    /** Parsed from an agent's own session transcript. Attribution is exact. */
    TRANSCRIPT,
    /** Pushed by an agent hook at the moment of the action. Attribution is exact. */
    HOOK,
    /** Raw filesystem change. No actor is known — do not guess one. */
    FS,
    /** A guard rule matched something an agent was about to do. */
    GUARD,
    /** The watcher talking about itself. */
    SYSTEM
  }

  public static Builder of(Source source, String type) {
    return new Builder(source, type);
  }

  public static final class Builder {
    private final Source source;
    private final String type;
    private String summary = "";
    private String path;
    private Long pid;
    private String agent;
    private String sessionId;
    private String mcpServer;
    private String subagent;
    private Object detail;

    private Builder(Source source, String type) {
      this.source = source;
      this.type = type;
    }

    public Builder summary(String s) {
      this.summary = s;
      return this;
    }

    public Builder path(String p) {
      this.path = p;
      return this;
    }

    public Builder pid(Long p) {
      this.pid = p;
      return this;
    }

    public Builder agent(String a) {
      this.agent = a;
      return this;
    }

    public Builder session(String s) {
      this.sessionId = s;
      return this;
    }

    public Builder mcpServer(String server) {
      this.mcpServer = server;
      return this;
    }

    public Builder subagent(String kind) {
      this.subagent = kind;
      return this;
    }

    public Builder detail(Object d) {
      this.detail = d;
      return this;
    }

    public Builder detail(String k, Object v) {
      this.detail = Map.of(k, v);
      return this;
    }

    WatchEvent build(long seq) {
      return new WatchEvent(
          seq,
          Instant.now(),
          source,
          type,
          summary,
          path,
          pid,
          agent,
          sessionId,
          mcpServer,
          subagent,
          detail);
    }
  }
}
