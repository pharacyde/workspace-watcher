package be.kleisli.ww.claude;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Layer 1b: hook events delivered through the filesystem.
 *
 * <p>A hook is a fresh, short-lived process per tool call, which makes a persistent transport
 * pointless — there is nothing to amortise, so a WebSocket would pay its handshake every single
 * time. Measured on this machine: a file write costs about 5 ms, an HTTP POST 20 ms, and a
 * graphql-ws handshake 50 ms plus a hard dependency on node.
 *
 * <p>The decisive argument is not speed though. A spooled event <b>survives the watcher being
 * down</b>: it sits on disk and is picked up whenever the watcher next starts. Over HTTP that same
 * event is simply lost. For a tool whose job is to not miss things, that settles it.
 *
 * <p>The writer creates a temporary file and renames it into place, so this reader can never
 * observe a half-written payload.
 */
@Service
public class HookSpoolService {

  private static final Logger log = LoggerFactory.getLogger(HookSpoolService.class);
  private static final Duration MAX_AGE = Duration.ofHours(1);
  private static final int MAX_PER_POLL = 500;

  private final WatcherProperties props;
  private final EventBus bus;
  private final ObjectMapper mapper = new ObjectMapper();

  public HookSpoolService(WatcherProperties props, EventBus bus) {
    this.props = props;
    this.bus = bus;
  }

  @Scheduled(fixedDelayString = "${watcher.spool-poll-ms:200}")
  public void drain() {
    Path spool = props.spoolPath();
    if (!Files.isDirectory(spool)) {
      return;
    }
    List<Path> files;
    try (Stream<Path> stream = Files.list(spool)) {
      // Oldest first, so the feed keeps the order the agent produced. Modification time
      // rather than filename: APFS timestamps are nanosecond-granular, while a filename
      // built from `date` in a shell script only resolves to the second.
      files =
          stream
              .filter(f -> f.getFileName().toString().endsWith(".json"))
              .sorted(
                  Comparator.comparing(HookSpoolService::modifiedAt)
                      .thenComparing(Path::getFileName))
              .limit(MAX_PER_POLL)
              .toList();
    } catch (IOException e) {
      log.debug("cannot list spool {}: {}", spool, e.toString());
      return;
    }

    for (Path file : files) {
      try {
        if (isStale(file)) {
          // A watcher that was off for a week should not replay a week of history.
          Files.deleteIfExists(file);
          continue;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        HookEvents.publish(bus, mapper, raw, "spool");
      } catch (IOException | RuntimeException e) {
        log.debug("cannot read spool file {}: {}", file, e.toString());
      } finally {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          log.debug("cannot delete spool file {}: {}", file, e.toString());
        }
      }
    }
  }

  private static long modifiedAt(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }

  private boolean isStale(Path file) throws IOException {
    Instant modified = Files.getLastModifiedTime(file).toInstant();
    return modified.isBefore(Instant.now().minus(MAX_AGE));
  }
}
