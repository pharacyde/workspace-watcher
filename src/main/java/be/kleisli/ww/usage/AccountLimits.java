package be.kleisli.ww.usage;

import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How much of a subscription's limits has been used, as Claude Code last saw it.
 *
 * <p>This project used to state that consumption against the limits is not knowable locally. That
 * was wrong, and re-measuring is what found it: Claude Code caches the answer it fetched in {@code
 * cachedUsageUtilization} in {@code ~/.claude.json} - the same file {@link Billing} already reads -
 * with a percentage and an exact {@code resets_at} per window. Nothing is guessed and the account
 * credential stays untouched; a file that is there anyway is read.
 *
 * <p>Two things keep it honest. The figure is a cache with a {@code fetchedAtMs}, so it is reported
 * with the moment it was taken and can announce itself as old. And a window whose {@code resets_at}
 * has passed has rolled over since - measured here, a five-hour window read at 7% that had reset
 * fourteen hours before - so it is marked expired rather than presented as the current standing.
 *
 * <p>Percentages only: {@code limit_dollars} is null on a subscription, which is the whole point of
 * a subscription.
 */
@Component
public class AccountLimits {

  private static final Logger log = LoggerFactory.getLogger(AccountLimits.class);

  /**
   * One limit window.
   *
   * <p>{@code kind} is Claude Code's own name for it ({@code session}, {@code weekly_all}, {@code
   * weekly_scoped}), passed through rather than translated: a name invented here would have to be
   * re-invented for every window Anthropic adds, and this list grows.
   */
  public record Window(
      String kind,
      String group,
      double percent,
      String severity,
      String resetsAt,
      String scope,
      boolean active,
      boolean expired) {}

  /** What the cache held, and when it was taken. */
  public record Snapshot(String fetchedAt, List<Window> windows) {}

  private final ObjectMapper mapper;
  private final Path config;

  private record Cached(long modified, long size, Snapshot snapshot) {}

  private volatile Cached cache;

  // Two constructors, so the one Spring should use has to say so. Without this the context
  // failed to start with "No default constructor found" - which no test caught, because a test
  // picks the constructor itself.
  @Autowired
  public AccountLimits(ObjectMapper mapper, WatcherProperties props) {
    this(mapper, props.claudeConfigPath());
  }

  AccountLimits(ObjectMapper mapper, Path config) {
    this.mapper = mapper;
    this.config = config;
  }

  /**
   * The limits as last cached, or null when this machine has no such record.
   *
   * <p>Re-read only when the file changed. It is a hundred kilobytes of JSON and Claude Code
   * rewrites it constantly for reasons that have nothing to do with usage, so the mtime is checked
   * rather than trusted to be stable - but parsing it on every poll of a header pill would be
   * paying for that file over and over.
   */
  public Snapshot current() {
    try {
      if (!Files.isRegularFile(config)) {
        // An atomic rewrite is unlink and rename, so a poll can land in the window where the file
        // is not there. That is the same flicker the catch below exists to prevent, and returning
        // null here walked straight past it.
        Cached seen = cache;
        return seen == null ? null : refreshExpiry(seen.snapshot());
      }
      long modified = Files.getLastModifiedTime(config).toMillis();
      long size = Files.size(config);
      Cached seen = cache;
      if (seen != null && seen.modified() == modified && seen.size() == size) {
        return refreshExpiry(seen.snapshot());
      }
      Snapshot snapshot = read(mapper, config);
      cache = new Cached(modified, size, snapshot);
      return snapshot;
    } catch (IOException | RuntimeException e) {
      // Claude Code rewrites this file constantly, so a poll can land on a half-written one. Never
      // having a figure is worth showing nothing; failing to re-read one is not - that made the
      // whole block flicker away and back. Keep the last good answer.
      log.debug("cannot read limits from {}: {}", config, e.toString());
      Cached seen = cache;
      return seen == null ? null : refreshExpiry(seen.snapshot());
    }
  }

  /**
   * Recomputes which windows have rolled over.
   *
   * <p>The cached parse stays valid as the file sits unchanged, but the clock does not: a window
   * that was current when it was parsed expires while the same bytes are on disk. Without this a
   * five-hour figure would keep being presented as current for as long as nobody wrote the file.
   */
  private static Snapshot refreshExpiry(Snapshot snapshot) {
    if (snapshot == null) {
      return null;
    }
    Instant now = Instant.now();
    return new Snapshot(
        snapshot.fetchedAt(),
        snapshot.windows().stream()
            .map(
                w ->
                    new Window(
                        w.kind(),
                        w.group(),
                        w.percent(),
                        w.severity(),
                        w.resetsAt(),
                        w.scope(),
                        w.active(),
                        expired(w.resetsAt(), now)))
            .toList());
  }

  static Snapshot read(ObjectMapper mapper, Path config) {
    JsonNode cached = mapper.readTree(config.toFile()).path("cachedUsageUtilization");
    if (cached.isMissingNode() || cached.isNull()) {
      return null;
    }
    long fetchedAtMs = cached.path("fetchedAtMs").asLong(0);
    JsonNode utilization = cached.path("utilization");
    Instant now = Instant.now();

    List<Window> windows = new ArrayList<>();
    for (JsonNode limit : utilization.path("limits")) {
      String resetsAt = limit.path("resets_at").asString(null);
      windows.add(
          new Window(
              limit.path("kind").asString(""),
              limit.path("group").asString(""),
              limit.path("percent").asDouble(0),
              limit.path("severity").asString(""),
              resetsAt,
              limit.path("scope").path("model").path("display_name").asString(null),
              limit.path("is_active").asBoolean(false),
              expired(resetsAt, now)));
    }
    if (windows.isEmpty()) {
      // Older caches carry the two windows as named fields and no limits[] array. Reading both
      // shapes costs a dozen lines and means the pill does not go blank on a different version.
      named(utilization, "five_hour", now).ifPresent(windows::add);
      named(utilization, "seven_day", now).ifPresent(windows::add);
    }
    if (windows.isEmpty()) {
      return null;
    }
    // Null rather than the epoch when the cache does not say when it was taken: "fetched
    // 20694 days ago" is an invented fact, and this figure is only honest with its age attached.
    String fetchedAt = fetchedAtMs > 0 ? Instant.ofEpochMilli(fetchedAtMs).toString() : null;
    return new Snapshot(fetchedAt, List.copyOf(windows));
  }

  private static Optional<Window> named(JsonNode utilization, String kind, Instant now) {
    JsonNode node = utilization.path(kind);
    if (!node.isObject()) {
      return Optional.empty();
    }
    String resetsAt = node.path("resets_at").asString(null);
    return Optional.of(
        new Window(
            kind,
            kind,
            node.path("utilization").asDouble(0),
            // Absent is not locked. MissingNode.isNull() is false, so testing for null said
            // "locked" about every window in a cache that simply omits the field when it is fine.
            node.path("locked_reason").asString(null) == null ? "normal" : "locked",
            resetsAt,
            null,
            true,
            expired(resetsAt, now)));
  }

  private static boolean expired(String resetsAt, Instant now) {
    if (resetsAt == null || resetsAt.isBlank()) {
      return false;
    }
    try {
      return OffsetDateTime.parse(resetsAt).toInstant().isBefore(now);
    } catch (RuntimeException e) {
      // An unparseable timestamp says nothing about whether the window rolled over; claiming it
      // did would hide a live figure.
      return false;
    }
  }
}
