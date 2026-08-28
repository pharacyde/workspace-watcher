package be.kleisli.ww.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class PricingTest {

  @TempDir Path tmp;

  private WatcherProperties props;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    props = new WatcherProperties();
    props.setDatabase(tmp.resolve("state/events.db").toString());
  }

  private void writeOverride(String json) throws IOException {
    Path file = tmp.resolve("state/pricing.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("knows the models it ships with")
  void knowsBundledModels() {
    Pricing pricing = new Pricing(mapper, props);

    assertThat(pricing.knows("claude-opus-5")).isTrue();
    assertThat(pricing.knows("claude-haiku-4-5")).isTrue();
    assertThat(pricing.knows("claude-from-the-future-9")).isFalse();
  }

  @Test
  @DisplayName("charges each kind of token at its own rate")
  void appliesTheCacheMultipliers() {
    // One million of each on Opus 5, at $5 input and $25 output: 5 + 25 + 6.25 + 10 + 0.5.
    // Getting these multipliers wrong is invisible - the number still looks plausible.
    Pricing pricing = new Pricing(mapper, props);
    TokenUsage tokens = new TokenUsage(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000);

    assertThat(pricing.cost("claude-opus-5", tokens)).isCloseTo(46.75, within(0.001));
  }

  @Test
  @DisplayName("an unpriced model returns null rather than zero")
  void unknownModelIsNull() {
    // A confident zero propagates silently into a total; an admitted gap does not.
    assertThat(new Pricing(mapper, props).cost("claude-from-the-future-9", TokenUsage.NONE))
        .isNull();
  }

  @Test
  @DisplayName("a file beside the database overrides the bundled rates")
  void overrideFileWins() throws IOException {
    // Rates are a snapshot and they change; correcting one must not need a release.
    writeOverride(
        """
        {"models": {"claude-opus-5": {"input": 100.0, "output": 200.0}}}\
        """);

    Pricing pricing = new Pricing(mapper, props);
    assertThat(pricing.cost("claude-opus-5", new TokenUsage(1_000_000, 0, 0, 0, 0)))
        .isCloseTo(100.0, within(0.001));
  }

  @Test
  @DisplayName("an override adds models without dropping the bundled ones")
  void overrideMerges() throws IOException {
    writeOverride(
        """
        {"models": {"claude-something-new": {"input": 1.0, "output": 2.0}}}\
        """);

    Pricing pricing = new Pricing(mapper, props);
    assertThat(pricing.knows("claude-something-new")).isTrue();
    assertThat(pricing.knows("claude-opus-5")).isTrue();
  }

  @Test
  @DisplayName("the cache multipliers can be corrected too")
  void overrideCanChangeMultipliers() throws IOException {
    writeOverride(
        """
        {"cacheReadMultiplier": 0.5, "models": {"claude-opus-5": {"input": 10.0, "output": 20.0}}}\
        """);

    Pricing pricing = new Pricing(mapper, props);
    assertThat(pricing.cost("claude-opus-5", new TokenUsage(0, 0, 0, 0, 1_000_000)))
        .isCloseTo(5.0, within(0.001));
  }

  @Test
  @DisplayName("a broken override leaves the bundled rates standing")
  void brokenOverrideIsIgnored() throws IOException {
    // A file someone edited by hand is the likeliest one to be malformed, and refusing to price
    // anything because of it would be a worse answer than pricing it from the table we shipped.
    writeOverride("{ this is not json");

    Pricing pricing = new Pricing(mapper, props);
    assertThat(pricing.cost("claude-opus-5", new TokenUsage(1_000_000, 0, 0, 0, 0)))
        .isCloseTo(5.0, within(0.001));
  }

  @Test
  @DisplayName("no tokens costs nothing")
  void zeroTokensCostNothing() {
    assertThat(new Pricing(mapper, props).cost("claude-opus-5", TokenUsage.NONE))
        .isCloseTo(0.0, within(0.000001));
  }
}
