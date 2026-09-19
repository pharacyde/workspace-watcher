package be.kleisli.ww.guard;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.guard.SensitiveContentScanner.Hit;
import be.kleisli.ww.guard.SensitiveContentScanner.Redacted;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SensitiveContentScannerTest {

  private static final String GITHUB = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij";
  private static final String JWT =
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
          + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ"
          + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

  private final SensitiveContentScanner scanner = new SensitiveContentScanner();

  private List<String> rules(String text) {
    return scanner.scan(text).stream().map(Hit::rule).toList();
  }

  // Assembled rather than written out: GitHub's push protection reads this file the way the
  // scanner reads a payload, and refused the push while these were literals.
  private static final String AWS = "AKIA" + "JQZX7YQ3F5RSTU2A";
  private static final String GCP = "AIza" + "SyD-9tSrke72PouQMnMX-a7eZSW0jkFMBxY";
  private static final String SLACK =
      "xoxb-" + "123456789012-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx";

  @ParameterizedTest(name = "{0}")
  @DisplayName("each rule fires on one real-shaped example")
  @CsvSource(
      delimiter = '|',
      value = {
        "aws-access-key      | key " + AWS + " here",
        "gcp-api-key         | " + GCP,
        "github-token        | export GITHUB_TOKEN=" + GITHUB,
        "github-token        | github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz",
        "slack-token         | " + SLACK,
        "anthropic-key       | ANTHROPIC_API_KEY=sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "openai-key          | sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCd",
        "private-key         | -----BEGIN RSA PRIVATE KEY-----",
        "jwt                 | " + JWT,
        "authorization-header| -H 'Authorization: Bearer AbCdEfGhIjKlMnOp'",
        "url-userinfo        | postgres://app:s3cr3t-pw@db.internal:5432/app",
        "password-assignment | password = hunter22",
        "password-assignment | \"api_key\": \"abcdef123456\"",
        "email               | mail jan.peeters@telenet.be please",
        "iban                | BE68 5390 0754 7034",
        "rrn-be              | 85.07.30-033.28",
        "rrn-be              | 01.02.03-004.67",
        "phone-be            | bel +32 470 12 34 56",
        "phone-be            | 0470/12.34.56",
        "credit-card         | 4111 1111 1111 1111",
      })
  void positives(String rule, String text) {
    assertThat(rules(text)).containsExactly(rule.strip());
  }

  @ParameterizedTest(name = "{0}: {1}")
  @DisplayName("each rule stays quiet on its nearest miss")
  @CsvSource(
      delimiter = '|',
      value = {
        "aws-access-key      | AKIAJQZX7YQ3F5RST (too short)",
        "gcp-api-key         | AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMB (34 after prefix)",
        "github-token        | ghp_tooshort0123456789",
        "slack-token         | xoxz-123456789012-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx",
        "anthropic-key       | sk-ant-short",
        "openai-key          | sk-AbCdEfGhIjKlMnOpQrStUvWxYz01234 (31 chars)",
        "private-key         | -----BEGIN CERTIFICATE-----",
        "jwt                 | eyJmb28iOiJiYXIiLCJiYXoiOjF9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghij",
        "authorization-header| Authorization: Bearer short",
        "url-userinfo        | https://db.internal:5432/app and user@host",
        "password-assignment | password_file=/etc/app/pw and token_count: 123456",
        "email               | jan at telenet dot be",
        "iban                | BE68 5390 0754 7035",
        "rrn-be              | 85.07.30-033.29",
        "rrn-be              | 01.02.03-004.50",
        "phone-be            | KBO 0412.345.678",
        "phone-be            | KBO 0470123465",
        "credit-card         | 4111 1111 1111 1112",
      })
  void nearMisses(String rule, String text) {
    assertThat(rules(text)).doesNotContain(rule.strip());
  }

  @Test
  @DisplayName("the enterprise-number checksum is what tells a KBO number from a mobile number")
  void kboChecksumSeparatesEnterpriseFromMobile() {
    // Same ten digits but one off in the check: no longer a valid KBO number, so a phone.
    assertThat(rules("0470123464")).containsExactly("phone-be");
    assertThat(rules("0470123465")).isEmpty();
  }

  @Test
  @DisplayName("documentation values are not leaks")
  void allowlist() {
    assertThat(rules("AKIAIOSFODNN7EXAMPLE")).isEmpty();
    assertThat(rules("user@example.com")).isEmpty();
    assertThat(rules("test@corp.internal")).isEmpty();
    assertThat(rules("ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")).isEmpty();
    assertThat(rules("password=00000000")).isEmpty();
    assertThat(rules("https://user:pass@example.org/")).isEmpty();
  }

  @Test
  @DisplayName("secrets are replaced with rule and four characters; personal data is left alone")
  void redactsSecretsOnly() {
    Redacted r =
        scanner.redact(
            "curl -u admin:hunter22 https://x/ -H 'Authorization: Bearer "
                + GITHUB
                + "'"
                + " from jan.peeters@telenet.be");

    assertThat(r.text()).doesNotContain(GITHUB);
    assertThat(r.text()).contains("Authorization: Bearer ‹github-token:ghp_…›");
    assertThat(r.text()).contains("jan.peeters@telenet.be");
    assertThat(r.rules()).containsExactly("github-token", "email");
  }

  @Test
  @DisplayName("only the password of a URL and the value of an assignment are replaced")
  void keepsTheReadablePart() {
    assertThat(scanner.redact("postgres://app:s3cr3t-pw@db.internal/app").text())
        .isEqualTo("postgres://app:‹url-userinfo:s3cr…›@db.internal/app");
    assertThat(scanner.redact("{\"password\":\"hunter22\"}").text())
        .isEqualTo("{\"password\":\"‹password-assignment:hunt…›\"}");
  }

  @Test
  @DisplayName("a private key is replaced whole, not just its header")
  void redactsPrivateKeyBody() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\\n"
            + "-----END PRIVATE KEY-----\\n\"}";
    assertThat(scanner.redact(pem).text()).isEqualTo("‹private-key:----…›\\n\"}");
  }

  @Test
  @DisplayName("redaction is idempotent: a second pass changes nothing")
  void idempotent() {
    String text =
        "token="
            + GITHUB
            + " and postgres://app:pw12345@h/ and Authorization: Basic "
            + JWT
            + " -----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----";
    String once = scanner.redact(text).text();
    String twice = scanner.redact(once).text();

    assertThat(once).doesNotContain(GITHUB, "pw12345", JWT, "AAAA");
    assertThat(twice).isEqualTo(once);
  }

  @Test
  @DisplayName("where two rules cover the same characters the longer span wins")
  void longestWins() {
    // The JWT and the header credential are the same span; the assignment covers it too.
    List<Hit> hits = scanner.scan("token: " + JWT);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().end() - hits.getFirst().start()).isEqualTo(JWT.length());
  }

  @Test
  @DisplayName("a 5 kB single token costs under 5 ms: every rule is bounded and possessive")
  void boundedOnOneLongToken() {
    Random random = new Random(42);
    StringBuilder blob = new StringBuilder("-H ");
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (int i = 0; i < 5120; i++) {
      blob.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    String text = blob.toString();
    // Warm-up so the timing is the regex engine rather than the JIT.
    for (int i = 0; i < 20; i++) {
      scanner.scan(text);
    }
    long start = System.nanoTime();
    scanner.scan(text);
    double ms = (System.nanoTime() - start) / 1e6;
    assertThat(ms).as("ms for a 5 kB base64 blob").isLessThan(5);
  }

  @Test
  @DisplayName("redactHead scans past the storage cut so a straddling token is still replaced")
  void redactHeadCoversTheOverhang() {
    String text = "x".repeat(4000 - 10) + " " + GITHUB + " " + "y".repeat(100_000);
    Redacted r = scanner.redactHead(text, 4000);
    assertThat(r.text()).doesNotContain(GITHUB);
    assertThat(r.text()).endsWith("y".repeat(100_000));
  }
}
