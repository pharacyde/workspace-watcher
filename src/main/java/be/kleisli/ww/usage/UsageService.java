package be.kleisli.ww.usage;

import be.kleisli.ww.claude.TranscriptLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
      List<String> unpricedModels,
      boolean billedPerToken,
      String billingMode,
      String plan,
      TokenUsage last5h,
      TokenUsage last7d) {}

  private record Entry(long epochSecond, String model, TokenUsage tokens) {}

  /**
   * Counts each assistant message once.
   *
   * <p>Claude Code writes one transcript record per content block - thinking, text, tool_use - and
   * every one of them repeats the identical, complete usage block for the whole message. Summing
   * the records therefore multiplies a message's tokens by how many blocks it happened to have.
   * Measured on this project: 1063 records against 458 messages, inflating the total by 58%.
   *
   * <p>This is the kind of error that does not announce itself. The number stays plausible, the
   * ratios between token kinds stay intact, and only the magnitude is wrong.
   */
  private static boolean firstTimeSeen(JsonNode message, Set<String> seen) {
    String id = message.path("id").asString(null);
    // Without an id there is nothing to deduplicate on; counting it is the lesser risk.
    return id == null || id.isBlank() || seen.add(id);
  }

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
    for (Path transcript : locator.forCosting()) {
      if (sessionId != null && !sessionId.equals(sessionFor(transcript))) {
        continue;
      }
      read(transcript).forEach((model, usage) -> byModel.merge(model, usage, TokenUsage::plus));
    }
    return summarise(byModel);
  }

  /**
   * The session a transcript belongs to.
   *
   * <p>For a session transcript that is the file name; for a subagent's it is the directory two
   * levels up, because a subagent's tokens are spent on behalf of the session that delegated to it
   * and belong on that session's bill.
   */
  private static String sessionFor(Path transcript) {
    String name = transcript.getFileName().toString();
    if (name.startsWith("agent-")) {
      return TranscriptLocator.sessionOf(transcript);
    }
    return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
  }

  private Summary summarise(Map<String, TokenUsage> byModel) {
    List<ModelUsage> models = new ArrayList<>();
    List<String> unpriced = new ArrayList<>();
    TokenUsage total = TokenUsage.NONE;
    double cost = 0;

    for (Map.Entry<String, TokenUsage> entry : byModel.entrySet()) {
      Double modelCost = pricing.cost(entry.getKey(), entry.getValue());
      models.add(new ModelUsage(entry.getKey(), entry.getValue(), modelCost));
      total = total.plus(entry.getValue());
      if (modelCost != null) {
        cost += modelCost;
        continue;
      }
      // A model with no tokens cannot change a total, so it is not worth mentioning. Claude Code
      // records synthetic assistant messages under "<synthetic>" with an all-zero usage block.
      if (entry.getValue().total() > 0) {
        unpriced.add(entry.getKey());
      }
    }
    models.sort(Comparator.comparingLong((ModelUsage m) -> m.tokens().total()).reversed());
    // What can be priced is priced, and what cannot is named. Collapsing the whole figure to null
    // because of one unknown model hid the cost of everything else - and a locally run model, which
    // is the common case here, costs nothing at all.
    return new Summary(
        models,
        total,
        cost,
        List.copyOf(unpriced),
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
    Set<String> seen = new HashSet<>();
    long horizon = Instant.now().getEpochSecond() - RECENT_SECONDS;
    try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
      lines.forEach(line -> accumulate(line, byModel, recent, horizon, seen));
    } catch (IOException | RuntimeException e) {
      log.debug("cannot read usage from {}: {}", transcript, e.toString());
      return Map.of();
    }
    cache.put(transcript, new Cached(size, byModel, recent));
    return byModel;
  }

  private void accumulate(
      String line,
      Map<String, TokenUsage> byModel,
      List<Entry> recent,
      long horizon,
      Set<String> seen) {
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
    if (!firstTimeSeen(message, seen)) {
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

  /** Tokens in one slice of a timeline, split the way the cost is. */
  public record Bucket(int index, String from, long total, long output, long cacheRead) {}

  /**
   * Token use over a range, bucketed for a timeline.
   *
   * <p>A different question from event density: a hundred file events and one enormous prompt look
   * alike in a count of events and nothing alike in what they cost.
   */
  public List<Bucket> activity(String since, String until, int buckets) {
    long from;
    long to;
    try {
      from = Instant.parse(since).getEpochSecond();
      to = Instant.parse(until).getEpochSecond();
    } catch (RuntimeException e) {
      return List.of();
    }
    int slices = Math.clamp(buckets, 1, 2000);
    long width = Math.max(1, (to - from) / slices);

    Map<Integer, long[]> sums = new LinkedHashMap<>();
    for (Path transcript : locator.forCosting()) {
      read(transcript);
      Cached cached = cache.get(transcript);
      if (cached == null) {
        continue;
      }
      for (Entry entry : cached.recent()) {
        if (entry.epochSecond() < from || entry.epochSecond() >= to) {
          continue;
        }
        int index = (int) ((entry.epochSecond() - from) / width);
        long[] slot = sums.computeIfAbsent(index, k -> new long[3]);
        slot[0] += entry.tokens().total();
        slot[1] += entry.tokens().output();
        slot[2] += entry.tokens().cacheRead();
      }
    }
    return sums.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            e ->
                new Bucket(
                    e.getKey(),
                    Instant.ofEpochSecond(from + (long) e.getKey() * width).toString(),
                    e.getValue()[0],
                    e.getValue()[1],
                    e.getValue()[2]))
        .toList();
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
    for (Path transcript : locator.forCosting()) {
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
