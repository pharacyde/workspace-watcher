package be.kleisli.ww.guard;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Finds secrets and personal data in text an agent sent or received, and can blank the secrets.
 *
 * <p>Contract for Epic 18: the rules, their bounds and the measurements live in docs/collectors.md
 * under "Guard". A hit names a rule and a span; it never carries the text.
 */
@Component
public class SensitiveContentScanner {

  /** One match: the rule that fired and the half-open span it covers. */
  public record Hit(String rule, int start, int end) {}

  /** Text with its secrets replaced, and what was replaced. */
  public record Redacted(String text, List<Hit> hits) {}

  /** Every hit, secrets and personal data alike, in order of position. */
  public List<Hit> scan(CharSequence text) {
    return List.of();
  }

  /**
   * The text with each secret-rule hit replaced by {@code ‹rule:xxxx…›} (four leading characters,
   * so a reader can tell which token without being able to use it). Personal-data rules are only
   * reported, never replaced: their false positives are too many to be irreversible about.
   */
  public Redacted redact(CharSequence text) {
    return new Redacted(text.toString(), List.of());
  }

  /** Whether a rule is a secret (redacted) rather than personal data (flagged only). */
  public static boolean isSecret(String rule) {
    return false;
  }
}
