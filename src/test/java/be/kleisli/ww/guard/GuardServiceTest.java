package be.kleisli.ww.guard;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class GuardServiceTest {

  @TempDir Path tmp;

  private Path workspace;
  private EventBus bus;
  private GuardService guard;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
    bus = new EventBus(props);
    guard = new GuardService(props, new ActiveWorkspace(props), bus, new ObjectMapper());
  }

  private GuardService.Decision check(String tool, String field, String value) {
    return guard.check(
        """
        {"tool_name":"%s","session_id":"s1","tool_input":{"%s":"%s"}}\
        """
            .formatted(tool, field, value));
  }

  @Test
  @DisplayName("never returns DENY while only observing")
  void neverDeniesWhileObserving() {
    // Found end to end, not by a unit test: the rule matched, the event correctly said "would
    // block", and the decision handed back to the hook still said DENY - so the guard blocked
    // while switched off. "Off" has to mean the hook cannot block, not just that the wording
    // changes.
    assertThat(guard.config().enabled()).isFalse();
    GuardService.Decision decision = check("Write", "file_path", "/home/me/.ssh/id_rsa");

    assertThat(decision.action()).isEqualTo(GuardService.Action.WARN);
    assertThat(decision.reason()).isEqualTo("ssh keys");
  }

  @Test
  @DisplayName("denies a private key once enforcing")
  void deniesPrivateKeyWhenEnforcing() {
    guard.save(new GuardService.Config(true, false, guard.config().rules()));
    assertThat(check("Write", "file_path", "/home/me/.ssh/id_rsa").action())
        .isEqualTo(GuardService.Action.DENY);
  }

  @Test
  @DisplayName("warns rather than denies on an environment file")
  void warnsOnEnvironmentFile() {
    guard.save(new GuardService.Config(true, false, guard.config().rules()));
    GuardService.Decision decision = check("Edit", "file_path", "/repo/.env.production");
    assertThat(decision.action()).isEqualTo(GuardService.Action.WARN);
    assertThat(decision.reason()).contains("credentials");
  }

  @Test
  @DisplayName("leaves ordinary source files alone")
  void allowsOrdinaryFile() {
    assertThat(check("Edit", "file_path", workspace + "/src/App.java").action())
        .isEqualTo(GuardService.Action.ALLOW);
  }

  @Test
  @DisplayName("records what a rule caught even while only observing")
  void publishesEventWhenObserving() {
    assertThat(guard.config().enabled()).isFalse();
    check("Write", "file_path", "/home/me/.ssh/id_rsa");

    WatchEvent event = bus.replay().getFirst();
    assertThat(event.source()).isEqualTo(WatchEvent.Source.GUARD);
    // "would block" rather than "blocked": you see what a rule catches before it stops anything.
    assertThat(event.type()).isEqualTo("FLAGGED");
    assertThat(event.summary()).startsWith("would block");
  }

  @Test
  @DisplayName("says blocked once enforcement is on")
  void publishesBlockedWhenEnforcing() {
    guard.save(new GuardService.Config(true, false, guard.config().rules()));
    check("Write", "file_path", "/home/me/.ssh/id_rsa");

    assertThat(bus.replay().getFirst().type()).isEqualTo("DENIED");
  }

  @Test
  @DisplayName("matches a command rule against a shell command")
  void matchesCommandRule() {
    guard.save(new GuardService.Config(true, false, guard.config().rules()));
    assertThat(check("Bash", "command", "git push origin main --force").action())
        .isEqualTo(GuardService.Action.WARN);
    assertThat(check("Bash", "command", "git push origin main").action())
        .isEqualTo(GuardService.Action.ALLOW);
  }

  @Test
  @DisplayName("blocks files outside the workspace only when asked to")
  void deniesOutsideWorkspaceWhenConfigured() {
    assertThat(check("Edit", "file_path", "/somewhere/else/File.java").action())
        .isEqualTo(GuardService.Action.ALLOW);

    guard.save(new GuardService.Config(true, true, guard.config().rules()));
    assertThat(check("Edit", "file_path", "/somewhere/else/File.java").action())
        .isEqualTo(GuardService.Action.DENY);
    assertThat(check("Edit", "file_path", workspace + "/src/App.java").action())
        .isEqualTo(GuardService.Action.ALLOW);
  }

  @Test
  @DisplayName("allows unreadable input rather than blocking on it")
  void failsOpenOnGarbage() {
    // A hook holds the agent until this answers; refusing to parse is not a reason to stop work.
    assertThat(guard.check("{not json at all").action()).isEqualTo(GuardService.Action.ALLOW);
  }

  @Test
  @DisplayName("survives a rule someone typed wrong")
  void toleratesInvalidPatterns() {
    guard.save(
        new GuardService.Config(
            true,
            false,
            List.of(
                new GuardService.Rule(
                    GuardService.Kind.COMMAND, "([unclosed", GuardService.Action.DENY, "broken"),
                new GuardService.Rule(
                    GuardService.Kind.PATH, "**/secret.txt", GuardService.Action.DENY, "secret"))));

    assertThat(check("Bash", "command", "ls").action()).isEqualTo(GuardService.Action.ALLOW);
    assertThat(check("Write", "file_path", "/repo/secret.txt").action())
        .isEqualTo(GuardService.Action.DENY);
  }

  @Test
  @DisplayName("keeps a reason safe for a shell client to extract without jq")
  void sanitisesReason() {
    guard.save(
        new GuardService.Config(
            true,
            false,
            List.of(
                new GuardService.Rule(
                    GuardService.Kind.PATH,
                    "**/x.txt",
                    GuardService.Action.DENY,
                    "he said \"no\"\nand \\ left"))));

    assertThat(check("Write", "file_path", "/repo/x.txt").reason())
        .doesNotContain("\"")
        .doesNotContain("\\")
        .doesNotContain("\n");
  }

  @Test
  @DisplayName("persists configuration and reads it back")
  void persistsConfiguration() {
    guard.save(
        new GuardService.Config(
            true,
            true,
            List.of(
                new GuardService.Rule(
                    GuardService.Kind.PATH, "**/*.key", GuardService.Action.DENY, "key"))));

    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
    GuardService reloaded =
        new GuardService(props, new ActiveWorkspace(props), bus, new ObjectMapper());
    reloaded.load();

    assertThat(reloaded.config().enabled()).isTrue();
    assertThat(reloaded.config().denyOutsideWorkspace()).isTrue();
    assertThat(reloaded.config().rules())
        .singleElement()
        .extracting(GuardService.Rule::pattern)
        .isEqualTo("**/*.key");
  }
}
