package be.kleisli.ww.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AccountLimitsTest {

  @TempDir Path tmp;

  private AccountLimits limitsOf(String json) throws IOException {
    Path config = tmp.resolve(".claude.json");
    Files.writeString(config, json);
    return new AccountLimits(new ObjectMapper(), config);
  }

  private static String past() {
    return Instant.now().minus(2, ChronoUnit.HOURS).toString().replace("Z", "+00:00");
  }

  private static String future() {
    return Instant.now().plus(2, ChronoUnit.HOURS).toString().replace("Z", "+00:00");
  }

  @Test
  @DisplayName("reads the percentage and reset moment of every window")
  void readsLimits() throws IOException {
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
              "limits": [
                {"kind": "session", "group": "session", "percent": 7, "severity": "normal",
                 "resets_at": "%s", "scope": null, "is_active": false},
                {"kind": "weekly_scoped", "group": "weekly", "percent": 12, "severity": "normal",
                 "resets_at": "%s", "scope": {"model": {"display_name": "Fable"}},
                 "is_active": true}
              ]}}}
            """
                .formatted(future(), future()));

    AccountLimits.Snapshot snapshot = limits.current();

    assertThat(snapshot.fetchedAt()).isEqualTo("2026-08-28T19:15:41.187Z");
    assertThat(snapshot.windows()).hasSize(2);
    assertThat(snapshot.windows().getFirst().kind()).isEqualTo("session");
    assertThat(snapshot.windows().getFirst().percent()).isEqualTo(7);
    assertThat(snapshot.windows().getFirst().scope()).isNull();
    assertThat(snapshot.windows().getLast().scope()).isEqualTo("Fable");
    assertThat(snapshot.windows().getLast().active()).isTrue();
  }

  @Test
  @DisplayName("a window whose reset moment has passed is marked expired, not shown as current")
  void marksRolledOverWindows() throws IOException {
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
              "limits": [
                {"kind": "session", "group": "session", "percent": 7, "severity": "normal",
                 "resets_at": "%s", "is_active": false},
                {"kind": "weekly_all", "group": "weekly", "percent": 49, "severity": "normal",
                 "resets_at": "%s", "is_active": true}
              ]}}}
            """
                .formatted(past(), future()));

    AccountLimits.Snapshot snapshot = limits.current();

    assertThat(snapshot.windows().getFirst().expired()).isTrue();
    assertThat(snapshot.windows().getLast().expired()).isFalse();
  }

  @Test
  @DisplayName("falls back to the named windows when there is no limits array")
  void readsOlderShape() throws IOException {
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
              "five_hour": {"utilization": 7, "resets_at": "%s", "limit_dollars": null,
                            "locked_reason": null},
              "seven_day": {"utilization": 49, "resets_at": "%s", "limit_dollars": null,
                            "locked_reason": null}}}}
            """
                .formatted(future(), future()));

    AccountLimits.Snapshot snapshot = limits.current();

    assertThat(snapshot.windows())
        .extracting(AccountLimits.Window::kind)
        .containsExactly("five_hour", "seven_day");
    assertThat(snapshot.windows().getLast().percent()).isEqualTo(49);
  }

  @Test
  @DisplayName("an omitted locked_reason is normal, not locked")
  void absentIsNotLocked() throws IOException {
    // MissingNode.isNull() is false, so testing for null called every window locked - and the UI
    // paints a locked window amber, telling someone at 7% that their limit is spent.
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
              "five_hour": {"utilization": 7, "resets_at": "%s"}}}}
            """
                .formatted(future()));

    assertThat(limits.current().windows().getFirst().severity()).isEqualTo("normal");
  }

  @Test
  @DisplayName("an unknown fetch moment is null, not the epoch")
  void unknownFetchMoment() throws IOException {
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"utilization": {
              "limits": [{"kind": "session", "group": "session", "percent": 7,
                          "severity": "normal", "resets_at": "%s", "is_active": true}]}}}
            """
                .formatted(future()));

    assertThat(limits.current().fetchedAt()).isNull();
  }

  @Test
  @DisplayName("a failed re-read keeps the last good figure instead of blanking it")
  void keepsTheLastGoodFigure() throws IOException {
    Path config = tmp.resolve("rewritten.json");
    Files.writeString(
        config,
        """
        {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
          "limits": [{"kind": "weekly_all", "group": "weekly", "percent": 49,
                      "severity": "normal", "resets_at": "%s", "is_active": true}]}}}
        """
            .formatted(future()));
    AccountLimits limits = new AccountLimits(new ObjectMapper(), config);
    assertThat(limits.current().windows()).hasSize(1);

    // Claude Code rewrites this file constantly; a poll can land on a half-written one.
    Files.writeString(config, "{\"cachedUsageUtiliz");

    assertThat(limits.current().windows().getFirst().percent()).isEqualTo(49);
  }

  @Test
  @DisplayName("null when the machine has no cached figure, rather than a confident zero")
  void nullWithoutACache() throws IOException {
    assertThat(limitsOf("{\"oauthAccount\": {}}").current()).isNull();
    assertThat(new AccountLimits(new ObjectMapper(), tmp.resolve("absent.json")).current())
        .isNull();
  }

  @Test
  @DisplayName("a cached parse still expires: the file does not change, but the clock does")
  void expiryIsRecomputedOnACacheHit() throws IOException {
    // Reset a second out, so the second call - served from the cache - sits past it. The margin
    // has to cover the first parse, class loading included, on a runner doing three builds at once.
    String resetsAt = Instant.now().plusSeconds(1).toString().replace("Z", "+00:00");
    AccountLimits limits =
        limitsOf(
            """
            {"cachedUsageUtilization": {"fetchedAtMs": 1787944541187, "utilization": {
              "limits": [{"kind": "session", "group": "session", "percent": 7,
                          "severity": "normal", "resets_at": "%s", "is_active": true}]}}}
            """
                .formatted(resetsAt));

    assertThat(limits.current().windows().getFirst().expired()).isFalse();
    await(Instant.parse(resetsAt.replace("+00:00", "Z")));
    assertThat(limits.current().windows().getFirst().expired()).isTrue();
  }

  private static void await(Instant moment) {
    while (Instant.now().isBefore(moment)) {
      Thread.onSpinWait();
    }
  }
}
