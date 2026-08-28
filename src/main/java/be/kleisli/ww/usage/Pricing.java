package be.kleisli.ww.usage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a token costs, per model.
 *
 * <p>Rates are a snapshot, not a fact of nature - they change, and a tool that hardcodes them will
 * quietly lie. The bundled table is a starting point; a {@code pricing.json} beside the database
 * overrides it, so a stale figure is something you can correct rather than something you have to
 * wait for a release to fix.
 *
 * <p>An unknown model is reported as unpriced rather than guessed at. Showing a confident zero for
 * a model nobody has priced yet is worse than showing the tokens and admitting the cost is unknown.
 */
@Component
public class Pricing {

  private static final Logger log = LoggerFactory.getLogger(Pricing.class);

  public record Rates(double inputPerMillion, double outputPerMillion) {}

  private final Map<String, Rates> models = new HashMap<>();
  private double cacheWrite5m = 1.25;
  private double cacheWrite1h = 2.0;
  private double cacheRead = 0.1;

  private final ObjectMapper mapper;

  public Pricing(ObjectMapper mapper, be.kleisli.ww.core.WatcherProperties props) {
    this.mapper = mapper;
    load(bundled());
    Path override = overrideFile(props);
    if (Files.isRegularFile(override)) {
      try {
        load(Files.readString(override, StandardCharsets.UTF_8));
        log.info("pricing overridden from {}", override);
      } catch (IOException | RuntimeException e) {
        log.warn("cannot read {}: {}", override, e.toString());
      }
    }
  }

  private static Path overrideFile(be.kleisli.ww.core.WatcherProperties props) {
    Path database = Path.of(props.getDatabase()).toAbsolutePath().normalize();
    Path directory =
        database.getParent() != null
            ? database.getParent()
            : Path.of(System.getProperty("user.dir"));
    return directory.resolve("pricing.json");
  }

  private String bundled() {
    try (InputStream in = new ClassPathResource("pricing.json").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "{}";
    }
  }

  private void load(String json) {
    JsonNode root = mapper.readTree(json);
    cacheWrite5m = root.path("cacheWrite5mMultiplier").asDouble(cacheWrite5m);
    cacheWrite1h = root.path("cacheWrite1hMultiplier").asDouble(cacheWrite1h);
    cacheRead = root.path("cacheReadMultiplier").asDouble(cacheRead);
    root.path("models")
        .properties()
        .forEach(
            entry ->
                models.put(
                    entry.getKey(),
                    new Rates(
                        entry.getValue().path("input").asDouble(0),
                        entry.getValue().path("output").asDouble(0))));
  }

  public boolean knows(String model) {
    return model != null && models.containsKey(model);
  }

  /**
   * Cost in dollars for one model's tokens, or {@code null} when the model is not priced.
   *
   * <p>Cache reads are charged at a tenth of the input rate and cache writes at 1.25x or 2x
   * depending on how long they live. For an agent that keeps a large prefix warm across a long
   * session, those three lines are most of the bill - a tracker that counted only input and output
   * would be wrong by orders of magnitude.
   */
  public Double cost(String model, TokenUsage tokens) {
    Rates rates = models.get(model);
    if (rates == null) {
      return null;
    }
    double input = rates.inputPerMillion();
    return (tokens.input() * input
            + tokens.output() * rates.outputPerMillion()
            + tokens.cacheWrite5m() * input * cacheWrite5m
            + tokens.cacheWrite1h() * input * cacheWrite1h
            + tokens.cacheRead() * input * cacheRead)
        / 1_000_000d;
  }
}
