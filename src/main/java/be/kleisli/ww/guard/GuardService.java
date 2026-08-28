package be.kleisli.ww.guard;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rules about what an agent may touch, and the decision for one tool call.
 *
 * <p>This is the one place the project stops being a passive observer, so it is off by default and
 * has to be turned on deliberately, both here and by wiring the guard script into {@code
 * PreToolUse}. With it off, a matching rule still produces an event: you see what <em>would</em>
 * have been stopped before you let it stop anything.
 *
 * <p>It fails open. A hook blocks the agent until it answers, so a watcher that is slow, wedged or
 * gone must let the call through rather than wedge the agent with it. That does mean this is a
 * guardrail against an agent's mistakes and not a security boundary against a determined one -
 * anyone who can stop the watcher can bypass it - and it is the right trade for a development tool.
 */
@Service
public class GuardService {

  private static final Logger log = LoggerFactory.getLogger(GuardService.class);

  public enum Action {
    ALLOW,
    WARN,
    DENY
  }

  public enum Kind {
    /** Glob matched against a file path the tool is about to touch. */
    PATH,
    /** Regular expression matched against a shell command. */
    COMMAND
  }

  public record Rule(Kind kind, String pattern, Action action, String reason) {}

  public record Config(boolean enabled, boolean denyOutsideWorkspace, List<Rule> rules) {}

  public record Decision(Action action, String reason, String rule) {

    /**
     * Keeps a reason safe for a shell client to pull out of a JSON response.
     *
     * <p>The guard hook has no jq - requiring one would put a dependency in the path of every tool
     * call - so it extracts the reason with sed. Quotes, backslashes and newlines are removed here
     * rather than escaped there, because reasons are short human sentences and the alternative is
     * quoting rules living in a shell script.
     */
    public Decision {
      if (reason != null) {
        reason = reason.replaceAll("[\"\\\\\r\n]", " ").trim();
      }
    }
  }

  /**
   * What gets protected before anyone writes a rule.
   *
   * <p>Deliberately short and all about credentials or repository plumbing: things an agent has
   * essentially no legitimate reason to rewrite, where a false positive costs one confirmation and
   * a false negative can cost a leaked key.
   */
  private static final List<Rule> DEFAULTS =
      List.of(
          new Rule(Kind.PATH, "**/.env", Action.WARN, "environment file often holds credentials"),
          new Rule(Kind.PATH, "**/.env.*", Action.WARN, "environment file often holds credentials"),
          new Rule(Kind.PATH, "**/.git/config", Action.WARN, "changes where the repository pushes"),
          new Rule(Kind.PATH, "**/.ssh/**", Action.DENY, "ssh keys"),
          new Rule(Kind.PATH, "**/.aws/credentials", Action.DENY, "cloud credentials"),
          new Rule(Kind.PATH, "**/*.pem", Action.DENY, "private key"),
          new Rule(Kind.PATH, "**/id_rsa*", Action.DENY, "private key"),
          new Rule(
              Kind.COMMAND,
              "\\brm\\s+(-[a-zA-Z]*\\s+)*-?[a-zA-Z]*[rf][a-zA-Z]*\\s+/(\\s|$)",
              Action.DENY,
              "recursive delete of the filesystem root"),
          new Rule(Kind.COMMAND, "\\bgit\\s+push\\s+.*--force\\b", Action.WARN, "force push"));

  private final WatcherProperties props;
  private final ActiveWorkspace active;
  private final EventBus bus;
  private final ObjectMapper mapper;

  private volatile Config config = new Config(false, false, DEFAULTS);

  public GuardService(
      WatcherProperties props, ActiveWorkspace active, EventBus bus, ObjectMapper mapper) {
    this.props = props;
    this.active = active;
    this.bus = bus;
    this.mapper = mapper;
  }

  public Config config() {
    return config;
  }

  @PostConstruct
  void load() {
    Path file = configFile();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
      List<Rule> rules = new ArrayList<>();
      for (JsonNode node : root.path("rules")) {
        rules.add(
            new Rule(
                Kind.valueOf(node.path("kind").asString("PATH")),
                node.path("pattern").asString(""),
                Action.valueOf(node.path("action").asString("WARN")),
                node.path("reason").asString(null)));
      }
      config =
          new Config(
              root.path("enabled").asBoolean(false),
              root.path("denyOutsideWorkspace").asBoolean(false),
              rules.isEmpty() ? DEFAULTS : List.copyOf(rules));
      log.info("guard loaded from {} ({})", file, config.enabled() ? "enforcing" : "observing");
    } catch (IOException | RuntimeException e) {
      log.warn("cannot read guard rules from {}: {}", file, e.toString());
    }
  }

  public synchronized Config save(Config updated) {
    this.config = updated;
    Path file = configFile();
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, mapper.writeValueAsString(updated), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      log.warn("cannot write guard rules to {}: {}", file, e.toString());
    }
    return updated;
  }

  private Path configFile() {
    Path database = Path.of(props.getDatabase()).toAbsolutePath().normalize();
    Path directory =
        database.getParent() != null
            ? database.getParent()
            : Path.of(System.getProperty("user.dir"));
    return directory.resolve("guard.json");
  }

  /**
   * Decides one tool call and records what it decided.
   *
   * <p>The event is published whether or not enforcement is on, so the feed shows what a rule
   * caught even while the guard is only observing.
   */
  public Decision check(String payloadJson) {
    JsonNode payload;
    try {
      payload = mapper.readTree(payloadJson);
    } catch (RuntimeException e) {
      // Unreadable input is not a reason to stop an agent.
      return new Decision(Action.ALLOW, null, null);
    }

    String tool = payload.path("tool_name").asString("");
    JsonNode input = payload.path("tool_input");
    String filePath = firstNonBlank(input, "file_path", "notebook_path", "path");
    String command = input.path("command").asString(null);

    Decision matched = evaluate(filePath, command);

    // While observing, a DENY is reported as a WARN to the caller. The hook blocks on DENY and
    // nothing else, so this is what makes "off" actually mean off: the rule is still evaluated and
    // still recorded, it simply cannot stop anything until enforcement is switched on.
    Decision decision =
        !config.enabled() && matched.action() == Action.DENY
            ? new Decision(Action.WARN, matched.reason(), matched.rule())
            : matched;

    if (matched.action() != Action.ALLOW) {
      boolean enforced = config.enabled() && matched.action() == Action.DENY;
      bus.publish(
          WatchEvent.of(WatchEvent.Source.GUARD, enforced ? "DENIED" : "FLAGGED")
              .agent("claude-code")
              .session(payload.path("session_id").asString(null))
              .summary(
                  (enforced ? "blocked " : "would block ")
                      + tool
                      + (filePath != null ? "  " + filePath : "")
                      + "  — "
                      + matched.reason())
              .path(filePath)
              .detail("rule", matched.rule()));
    }
    return decision;
  }

  Decision evaluate(String filePath, String command) {
    if (filePath != null) {
      Path candidate = Path.of(filePath).toAbsolutePath().normalize();
      for (Rule rule : config.rules()) {
        if (rule.kind() == Kind.PATH && matchesGlob(rule.pattern(), candidate)) {
          return new Decision(rule.action(), rule.reason(), rule.pattern());
        }
      }
      Path workspace = active.get();
      if (config.denyOutsideWorkspace() && workspace != null && !candidate.startsWith(workspace)) {
        return new Decision(Action.DENY, "outside the workspace", "outsideWorkspace");
      }
    }

    if (command != null) {
      for (Rule rule : config.rules()) {
        if (rule.kind() == Kind.COMMAND && matchesRegex(rule.pattern(), command)) {
          return new Decision(rule.action(), rule.reason(), rule.pattern());
        }
      }
    }
    return new Decision(Action.ALLOW, null, null);
  }

  private static boolean matchesGlob(String pattern, Path path) {
    try {
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      return matcher.matches(path);
    } catch (RuntimeException e) {
      // A rule someone typed wrong must not take the guard down with it.
      return false;
    }
  }

  private static boolean matchesRegex(String pattern, String command) {
    try {
      return Pattern.compile(pattern).matcher(command).find();
    } catch (PatternSyntaxException e) {
      return false;
    }
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
