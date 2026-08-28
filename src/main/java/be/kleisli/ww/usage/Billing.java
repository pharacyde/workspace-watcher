package be.kleisli.ww.usage;

import be.kleisli.ww.core.WatcherProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether tokens are actually paid for one by one.
 *
 * <p>This decides what a cost figure is allowed to claim. On a Claude subscription nobody pays per
 * token - there is a flat fee and usage limits - so a dollar amount is what those tokens would have
 * cost at API rates. That is a useful measure of how heavy a session was and it is not a bill, and
 * a tool that shows it without saying which one it is has told a confident lie.
 *
 * <p>Detected rather than asked: an OAuth login recorded in {@code ~/.claude.json} with no {@code
 * ANTHROPIC_API_KEY} set is a subscription. Override with {@code watcher.billing}.
 */
@Component
public class Billing {

  private static final Logger log = LoggerFactory.getLogger(Billing.class);

  private final boolean billedPerToken;
  private final String mode;
  private final String plan;

  public Billing(WatcherProperties props) {
    String configured = props.getBilling() == null ? "auto" : props.getBilling().toLowerCase();
    this.mode =
        switch (configured) {
          case "api", "subscription" -> configured;
          default -> detect();
        };
    this.billedPerToken = "api".equals(mode);
    this.plan = readPlan();
    log.info(
        "billing is {} ({}), so cost is {}",
        mode,
        configured.equals(mode) ? "configured" : "detected",
        billedPerToken ? "what was spent" : "what these tokens would cost at API rates");
  }

  /** True when a dollar figure is money someone actually pays. */
  public boolean billedPerToken() {
    return billedPerToken;
  }

  public String mode() {
    return mode;
  }

  /**
   * The subscription tier, when one is recorded locally.
   *
   * <p>Only the plan is knowable from here. How much of it has been used is not: Claude Code keeps
   * no local record of consumption against the limits, and the figure its own {@code /usage}
   * command shows is fetched live with the account's OAuth credential. Reaching for that credential
   * to fill in a number is exactly the kind of thing this project's guard exists to stop.
   */
  public String plan() {
    return plan;
  }

  private static String readPlan() {
    Path config = Path.of(System.getProperty("user.home"), ".claude.json");
    try {
      if (!Files.isRegularFile(config)) {
        return null;
      }
      String text = Files.readString(config, StandardCharsets.UTF_8);
      Matcher matcher =
          Pattern.compile("\"organizationRateLimitTier\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
      if (matcher.find()) {
        return matcher.group(1);
      }
      matcher = Pattern.compile("\"organizationType\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
      return matcher.find() ? matcher.group(1) : null;
    } catch (Exception e) {
      log.debug("cannot read plan from {}: {}", config, e.toString());
      return null;
    }
  }

  private static String detect() {
    String key = System.getenv("ANTHROPIC_API_KEY");
    if (key != null && !key.isBlank()) {
      return "api";
    }
    Path config = Path.of(System.getProperty("user.home"), ".claude.json");
    try {
      if (Files.isRegularFile(config)
          && Files.readString(config, StandardCharsets.UTF_8).contains("\"oauthAccount\"")) {
        return "subscription";
      }
    } catch (Exception e) {
      log.debug("cannot read {}: {}", config, e.toString());
    }
    // Neither signal present: assume per-token, which is the reading that does not understate.
    return "api";
  }
}
