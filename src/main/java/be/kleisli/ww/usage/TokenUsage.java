package be.kleisli.ww.usage;

/**
 * Tokens of each kind.
 *
 * <p>Kept apart rather than summed because they are priced differently by up to twentyfold: a cache
 * read costs a tenth of an input token and a one-hour cache write costs twice one. Collapsing them
 * into a single number would make the total meaningless.
 */
public record TokenUsage(
    long input, long output, long cacheWrite5m, long cacheWrite1h, long cacheRead) {

  public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0, 0);

  public TokenUsage plus(TokenUsage other) {
    return new TokenUsage(
        input + other.input(),
        output + other.output(),
        cacheWrite5m + other.cacheWrite5m(),
        cacheWrite1h + other.cacheWrite1h(),
        cacheRead + other.cacheRead());
  }

  public long total() {
    return input + output + cacheWrite5m + cacheWrite1h + cacheRead;
  }
}
