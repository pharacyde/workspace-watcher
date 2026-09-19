package be.kleisli.ww.guard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  public record Redacted(String text, List<Hit> hits) {
    /** The distinct rules that fired, secrets and personal data alike, in order of first hit. */
    public List<String> rules() {
      return hits.stream().map(Hit::rule).distinct().toList();
    }
  }

  /**
   * How far past a storage limit the record-time hook points scan: longer than any one bounded
   * secret, so a token straddling the cut is still replaced before the cut is made.
   */
  public static final int OVERHANG = 8192;

  private static final String OPEN = "‹";
  private static final String CLOSE = "…›";
  private static final int EXCERPT = 4;

  /**
   * A rule: its pattern, whether the match (or one group of it) is a secret, and an optional check
   * a candidate must pass. The group is the part that gets replaced - a password keeps its keyword,
   * a URL keeps its user and host - so a reader still knows what kind of thing was there.
   */
  private record Rule(
      String name, Pattern pattern, int group, boolean secret, Predicate<String> check) {
    Rule(String name, String regex, boolean secret) {
      this(name, Pattern.compile(regex), 0, secret, s -> true);
    }

    Rule(String name, String regex, int group, boolean secret) {
      this(name, Pattern.compile(regex), group, secret, s -> true);
    }

    Rule(String name, String regex, boolean secret, Predicate<String> check) {
      this(name, Pattern.compile(regex), 0, secret, check);
    }
  }

  // Characters no token may run into: whitespace, quotes, the JSON escape, and our own markers.
  // Excluding the markers is what makes redact() idempotent - a second pass sees ‹rule:xxxx…› as
  // four characters and no keyword value.
  private static final String STOP = "\\s\"'\\\\‹›…";
  private static final String NOT_ALNUM_BEFORE = "(?<![A-Za-z0-9_])";
  private static final String NOT_ALNUM_AFTER = "(?![A-Za-z0-9_])";

  /**
   * A fixed prefix that must start a token: the literal first, the boundary looked back over it.
   * Leading with the lookbehind reads well but costs every rule 0.04 ms per 4 kB, because the
   * engine can only skip ahead to a literal when the pattern starts with one.
   */
  private static String token(String prefix) {
    return prefix + "(?<![A-Za-z0-9_]" + prefix + ")";
  }

  // "RSA ", "EC ", "ENCRYPTED " or nothing. Not [A-Z ]{0,20}+: possessive, that eats "PRIVATE
  // KEY" itself and the header no longer matches.
  private static final String PEM_LABEL = "(?:(?!PRIVATE)[A-Z]{1,12}+ ){0,2}+";

  private static final List<Rule> RULES =
      List.of(
          new Rule("aws-access-key", token("AKIA") + "[0-9A-Z]{16}+" + NOT_ALNUM_AFTER, true),
          new Rule("gcp-api-key", token("AIza") + "[0-9A-Za-z_-]{35}+" + NOT_ALNUM_AFTER, true),
          new Rule(
              "github-token", token("gh") + "[pousr]_[A-Za-z0-9]{36,255}+" + NOT_ALNUM_AFTER, true),
          // The same name twice rather than one alternation: a pattern that starts with a literal
          // is skipped ahead to, an alternation is tried at every position.
          new Rule(
              "github-token",
              token("github_pat_") + "[A-Za-z0-9_]{22,255}+" + NOT_ALNUM_AFTER,
              true),
          new Rule(
              "slack-token",
              token("xox") + "[baprs]-[A-Za-z0-9-]{10,255}+" + NOT_ALNUM_AFTER,
              true),
          new Rule(
              "anthropic-key", token("sk-ant-") + "[A-Za-z0-9_-]{20,255}+" + NOT_ALNUM_AFTER, true),
          new Rule(
              "openai-key",
              token("sk-") + "(?!ant-)[A-Za-z0-9_-]{32,255}+" + NOT_ALNUM_AFTER,
              true),
          new Rule(
              "private-key",
              "-----BEGIN "
                  + PEM_LABEL
                  + "PRIVATE KEY-----"
                  + "(?:[\\sA-Za-z0-9+/=\\\\]{0,8000}+(?:-----END "
                  + PEM_LABEL
                  + "PRIVATE KEY-----)?)?",
              true),
          new Rule(
              "jwt",
              token("eyJ")
                  + "[A-Za-z0-9_-]{8,2000}+\\.[A-Za-z0-9_-]{8,4000}+\\.[A-Za-z0-9_-]{8,2000}+",
              true,
              SensitiveContentScanner::jwtHeader),
          new Rule(
              "authorization-header",
              "(?i)authorization[\"']?\\s{0,4}+[:=]\\s{0,4}+[\"']?(?:Bearer|Basic|Token)\\s{1,4}+"
                  + "([^"
                  + STOP
                  + "]{8,4096}+)",
              1,
              true),
          new Rule(
              "url-userinfo",
              "[a-z][a-z0-9+.-]{0,15}+://[^"
                  + STOP
                  + "/@:]{1,64}+:([^"
                  + STOP
                  + "/@]{1,256}+)@"
                  + "[^"
                  + STOP
                  + "/:?#]{1,253}+",
              1,
              true),
          new Rule(
              "password-assignment",
              "(?i)\\b(?:password|passwd|pwd|secret|api[_-]?key|token)\\b[\"']?\\s{0,4}+[=:]\\s{0,4}+"
                  + "[\"']?([^"
                  + STOP
                  + ",;]{6,256}+)",
              1,
              true),
          new Rule(
              "email",
              "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}+@[A-Za-z0-9-]{1,63}+"
                  + "(?:\\.[A-Za-z0-9-]{1,63}+){1,4}+",
              false),
          new Rule(
              "iban",
              NOT_ALNUM_BEFORE
                  + "[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}+(?: ?[A-Z0-9]{1,4})?+"
                  + NOT_ALNUM_AFTER,
              false,
              SensitiveContentScanner::ibanChecksum),
          new Rule(
              "rrn-be",
              "(?<![0-9])[0-9]{2}[. -]?+[0-9]{2}[. -]?+[0-9]{2}[. -]?+[0-9]{3}[. -]?+[0-9]{2}"
                  + "(?![0-9])",
              false,
              SensitiveContentScanner::belgianNationalNumber),
          new Rule(
              "phone-be",
              "(?<![0-9A-Za-z.+])(?:\\+32 ?|0032 ?|0)4[0-9]{2}[ ./-]?+[0-9]{2}[ ./-]?+[0-9]{2}"
                  + "[ ./-]?+[0-9]{2}(?![0-9])",
              false,
              SensitiveContentScanner::belgianMobile),
          new Rule(
              "credit-card",
              "(?<![0-9])[0-9](?:[ -]?+[0-9]){12,18}+(?![0-9])",
              false,
              SensitiveContentScanner::luhn));

  private static final Set<String> SECRET_RULES =
      RULES.stream()
          .filter(Rule::secret)
          .map(Rule::name)
          .collect(java.util.stream.Collectors.toSet());

  /** Substrings, compared lower-case, that mark a match as documentation rather than a leak. */
  private static final List<String> ALLOWLIST =
      List.of("example.com", "example.org", "test@", "user@example", "akiaiosfodnn7example");

  /**
   * A placeholder rather than a value: a run of x, 0 or * where the secret would be. Judged on the
   * replaced span only, because a private key's body is long enough to contain anything.
   */
  private static final Pattern FILLER = Pattern.compile("(?i)(?:x{6,}|0{6,}|\\*{4,})");

  /** A hit plus the whole match it came from; overlaps are judged on the latter. */
  private record Candidate(Hit hit, int from, int to) {}

  /** Every hit, secrets and personal data alike, in order of position. */
  public List<Hit> scan(CharSequence text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    List<Candidate> candidates = new ArrayList<>();
    for (Rule rule : RULES) {
      Matcher m = rule.pattern().matcher(text);
      while (m.find()) {
        String whole = m.group();
        String span = m.group(rule.group());
        if (allowlisted(whole, span) || !rule.check().test(whole)) {
          continue;
        }
        Hit hit = new Hit(rule.name(), m.start(rule.group()), m.end(rule.group()));
        candidates.add(new Candidate(hit, m.start(), m.end()));
      }
    }
    return withoutOverlaps(candidates);
  }

  /**
   * The text with each secret-rule hit replaced by {@code ‹rule:xxxx…›} (four leading characters,
   * so a reader can tell which token without being able to use it). Personal-data rules are only
   * reported, never replaced: their false positives are too many to be irreversible about.
   */
  public Redacted redact(CharSequence text) {
    List<Hit> hits = scan(text);
    if (hits.stream().noneMatch(h -> isSecret(h.rule()))) {
      return new Redacted(text == null ? null : text.toString(), hits);
    }
    StringBuilder out = new StringBuilder(text.length());
    int from = 0;
    for (Hit hit : hits) {
      if (!isSecret(hit.rule())) {
        continue;
      }
      out.append(text, from, hit.start());
      out.append(OPEN)
          .append(hit.rule())
          .append(':')
          .append(text, hit.start(), Math.min(hit.end(), hit.start() + EXCERPT))
          .append(CLOSE);
      from = hit.end();
    }
    out.append(text, from, text.length());
    return new Redacted(out.toString(), hits);
  }

  /**
   * Redacts what is about to be stored under a length cap: the first {@code limit + OVERHANG}
   * characters. Anything past that is cut off by the caller anyway; scanning it would cost the
   * collector time for text nobody keeps, and a multi-megabyte tool_response is routine.
   */
  public Redacted redactHead(String text, int limit) {
    if (text == null) {
      return new Redacted(null, List.of());
    }
    int window = Math.min(text.length(), limit + OVERHANG);
    if (window == text.length()) {
      return redact(text);
    }
    Redacted head = redact(text.substring(0, window));
    return new Redacted(head.text() + text.substring(window), head.hits());
  }

  /** Whether a rule is a secret (redacted) rather than personal data (flagged only). */
  public static boolean isSecret(String rule) {
    return SECRET_RULES.contains(rule);
  }

  private static boolean allowlisted(String whole, String span) {
    String lower = whole.toLowerCase(java.util.Locale.ROOT);
    return ALLOWLIST.stream().anyMatch(lower::contains) || FILLER.matcher(span).find();
  }

  /**
   * Where two rules cover the same characters, a secret beats personal data, then the longer span
   * wins, then the earlier rule. The first matters: {@code scheme://user:pw@host} is also an e-mail
   * address, and the address is the longer of the two. Overlap is judged on the whole match, so the
   * address and the URL do collide even though only the password is replaced.
   */
  private static List<Hit> withoutOverlaps(List<Candidate> candidates) {
    List<Candidate> ranked = new ArrayList<>(candidates);
    ranked.sort(
        Comparator.comparing((Candidate c) -> !isSecret(c.hit().rule()))
            .thenComparingInt(c -> c.hit().start() - c.hit().end()));
    List<Candidate> kept = new ArrayList<>();
    for (Candidate c : ranked) {
      if (kept.stream().noneMatch(k -> c.from() < k.to() && k.from() < c.to())) {
        kept.add(c);
      }
    }
    return kept.stream().map(Candidate::hit).sorted(Comparator.comparingInt(Hit::start)).toList();
  }

  private static boolean jwtHeader(String token) {
    try {
      String header =
          new String(
              Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
              StandardCharsets.UTF_8);
      return header.startsWith("{") && header.contains("\"alg\"");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean ibanChecksum(String iban) {
    String compact = iban.replace(" ", "");
    if (compact.length() < 15 || compact.length() > 34) {
      return false;
    }
    String rearranged = compact.substring(4) + compact.substring(0, 4);
    int remainder = 0;
    for (char c : rearranged.toCharArray()) {
      int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
      remainder = (remainder * (value > 9 ? 100 : 10) + value) % 97;
    }
    return remainder == 1;
  }

  /**
   * Eleven digits: YYMMDD, a sequence of three, and 97 minus the first nine modulo 97 - or the same
   * with 2 000 000 000 added for people born from 2000 on. A random eleven-digit number passes one
   * of the two checks about 1 in 48 times, which is why this rule flags and never redacts.
   */
  private static boolean belgianNationalNumber(String match) {
    String digits = match.replaceAll("[^0-9]", "");
    if (digits.length() != 11) {
      return false;
    }
    int month = Integer.parseInt(digits.substring(2, 4));
    int day = Integer.parseInt(digits.substring(4, 6));
    // Months 21-32 and 41-52 are the "bis" numbers of people whose birth date was not certain.
    boolean plausibleDate = (month % 20 >= 1 && month % 20 <= 12 && month < 53) && day <= 31;
    if (!plausibleDate) {
      return false;
    }
    long base = Long.parseLong(digits.substring(0, 9));
    int check = Integer.parseInt(digits.substring(9));
    return check == 97 - (int) (base % 97) || check == 97 - (int) ((2_000_000_000L + base) % 97);
  }

  /**
   * A ten-digit 04xx number that also passes the enterprise-number check (last two = 97 minus the
   * first eight modulo 97) is far more likely a KBO number than a phone: that was the main false
   * positive when the rule was measured on real history.
   */
  private static boolean belgianMobile(String match) {
    String digits = match.replaceAll("[^0-9]", "");
    if (digits.length() == 10 && digits.startsWith("0")) {
      long head = Long.parseLong(digits.substring(0, 8));
      int check = Integer.parseInt(digits.substring(8));
      return check != 97 - (int) (head % 97);
    }
    return true;
  }

  private static boolean luhn(String match) {
    int sum = 0;
    boolean twice = false;
    for (int i = match.length() - 1; i >= 0; i--) {
      char c = match.charAt(i);
      if (!Character.isDigit(c)) {
        continue;
      }
      int digit = c - '0';
      if (twice) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      twice = !twice;
    }
    return sum % 10 == 0;
  }
}
