package be.kleisli.ww.usage;

import be.kleisli.ww.claude.TranscriptLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a session has spent.
 *
 * <p>Read from the transcripts rather than accumulated live, so the figure is the session's whole
 * total and not merely what happened since the watcher started - a number that begins at zero
 * halfway through a session is worse than no number.
 *
 * <p>Results are cached against the transcript's size, which only grows: an unchanged file is never
 * read twice, and an appended one is re-read in full. That is the cheap version of correct, and a
 * transcript is a few megabytes.
 */
@Service
public class UsageService {

  private static final Logger log = LoggerFactory.getLogger(UsageService.class);

  public record ModelUsage(String model, TokenUsage tokens, Double costUsd) {}

  /**
   * {@code costUsd} is null when any model involved has no price, rather than a confident zero.
   *
   * <p>{@code billedPerToken} says whether that figure is money anyone actually pays. On a
   * subscription it is not: it is what these tokens would have cost at API rates, which measures
   * how heavy a session was and is not a bill.
   */
  public record Summary(
      List<ModelUsage> models,
      TokenUsage tokens,
      Double costUsd,
      boolean billedPerToken,
      String billingMode,
      String plan,
      TokenUsage last5h,
      TokenUsage last7d) {}

  private record Entry(long epochSecond, String model, TokenUsage tokens) {}

  /**
   * Aggregate totals plus the recent entries, kept apart on purpose.
   *
   * <p>The aggregate answers "what has this session cost"; the entries answer "how much in the last
   * five hours", which is the shape a subscription's limits actually take. Only entries inside the
   * window are retained, so a long project does not accumulate its whole history in memory.
   */
  private record Cached(long size, Map<String, TokenUsage> byModel, List<Entry> recent) {}

  /** How far back windowed questions can reach. */
  private static final long RECENT_SECONDS = 7 * 24 * 3600;

  private final TranscriptLocator locator;
  private final Pricing pricing;
  private final Billing billing;
  private final ObjectMapper mapper;
  private final Map<Path, Cached> cache = new ConcurrentHashMap<>();

  public UsageService(
      TranscriptLocator locator, Pricing pricing, Billing billing, ObjectMapper mapper) {
    this.locator = locator;
    this.pricing = pricing;
    this.billing = billing;
    this.mapper = mapper;
  }

  /** Usage for one session, or for the whole workspace when {@code sessionId} is null. */
  public Summary summarise(String sessionId) {
    Map<String, TokenUsage> byModel = new LinkedHashMap<>();
    for (Path transcript : locator.transcripts()) {
      String name = transcript.getFileName().toString();
      if (sessionId != null && !name.equals(sessionId + ".jsonl")) {
        continue;
      }
      read(transcript).forEach((model, usage) -> byModel.merge(model, usage, TokenUsage::plus));
    }
    return summarise(byModel);
  }

  private Summary summarise(Map<String, TokenUsage> byModel) {
    List<ModelUsage> models = new ArrayList<>();
    TokenUsage total = TokenUsage.NONE;
    double cost = 0;
    boolean priced = true;

    for (Map.Entry<String, TokenUsage> entry : byModel.entrySet()) {
      Double modelCost = pricing.cost(entry.getKey(), entry.getValue());
      models.add(new ModelUsage(entry.getKey(), entry.getValue(), modelCost));
      total = total.plus(entry.getValue());
      if (modelCost == null) {
        priced = false;
      } else {
        cost += modelCost;
      }
    }
    models.sort(Comparator.comparingLong((ModelUsage m) -> m.tokens().total()).reversed());
    return new Summary(
        models,
        total,
        priced ? cost : null,
        billing.billedPerToken(),
        billing.mode(),
        billing.plan(),
        inLastSeconds(5 * 3600),
        inLastSeconds(7 * 24 * 3600));
  }

  private Map<String, TokenUsage> read(Path transcript) {
    long size;
    try {
      size = Files.size(transcript);
    } catch (IOException e) {
      return Map.of();
    }
    Cached cached = cache.get(transcript);
    if (cached != null && cached.size() == size) {
      return cached.byModel();
    }

    Map<String, TokenUsage> byModel = new LinkedHashMap<>();
    List<Entry> recent = new ArrayList<>();
    long horizon = Instant.now().getEpochSecond() - RECENT_SECONDS;
    try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
      lines.forEach(line -> accumulate(line, byModel, recent, horizon));
    } catch (IOException | RuntimeException e) {
      log.debug("cannot read usage from {}: {}", transcript, e.toString());
      return Map.of();
    }
    cache.put(transcript, new Cached(size, byModel, recent));
    return byModel;
  }

  private void accumulate(
      String line, Map<String, TokenUsage> byModel, List<Entry> recent, long horizon) {
    // Cheap rejection before parsing: most lines carry no usage at all.
    if (!line.contains("\"usage\"")) {
      return;
    }
    JsonNode root;
    try {
      root = mapper.readTree(line);
    } catch (RuntimeException e) {
      return;
    }
    JsonNode message = root.path("message");
    JsonNode usage = message.path("usage");
    if (usage.isMissingNode() || usage.isNull()) {
      return;
    }

    // cache_creation splits the write by how long it lives, and the two are priced differently.
    // Fall back to the undivided figure when the split is absent, counting it as the cheaper one.
    JsonNode creation = usage.path("cache_creation");
    long write1h = creation.path("ephemeral_1h_input_tokens").asLong(0);
    long write5m = creation.path("ephemeral_5m_input_tokens").asLong(0);
    if (write1h == 0 && write5m == 0) {
      write5m = usage.path("cache_creation_input_tokens").asLong(0);
    }

    TokenUsage tokens =
        new TokenUsage(
            usage.path("input_tokens").asLong(0),
            usage.path("output_tokens").asLong(0),
            write5m,
            write1h,
            usage.path("cache_read_input_tokens").asLong(0));
    String model = message.path("model").asString("unknown");
    byModel.merge(model, tokens, TokenUsage::plus);

    try {
      long at = Instant.parse(root.path("timestamp").asString("")).getEpochSecond();
      if (at >= horizon) {
        recent.add(new Entry(at, model, tokens));
      }
    } catch (RuntimeException e) {
      // No usable timestamp: it still counts towards the total, just not towards a window.
    }
  }

  /**
   * Tokens used in the last {@code seconds}, across every session in the workspace.
   *
   * <p>This is the shape a subscription's limits take - a rolling window - and it is the closest
   * honest answer available locally. It is consumption, not headroom: how much of an allowance
   * remains is not recorded anywhere on this machine.
   */
  public TokenUsage inLastSeconds(long seconds) {
    long from = Instant.now().getEpochSecond() - seconds;
    TokenUsage total = TokenUsage.NONE;
    for (Path transcript : locator.transcripts()) {
      read(transcript);
      Cached cached = cache.get(transcript);
      if (cached == null) {
        continue;
      }
      for (Entry entry : cached.recent()) {
        if (entry.epochSecond() >= from) {
          total = total.plus(entry.tokens());
        }
      }
    }
    return total;
  }
}
