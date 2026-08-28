package be.kleisli.ww.claude;

import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent sessions belonging to the workspace being watched.
 *
 * <p>One workspace often has several agents working in it at once, in separate terminals. Every
 * transcript and hook event already carries the session that produced it, so this register turns
 * that into something selectable: pick a workspace, then pick which agent in it to follow.
 *
 * <p>Sessions are read from the transcript files rather than from observed events, so a session
 * that has been quiet longer than the event buffer still appears.
 */
@Service
public class SessionRegistry {

  /** How recently a transcript must have been written to count as still running. */
  private static final Duration LIVE_WINDOW = Duration.ofMinutes(5);

  public record Entry(String id, String title, String lastActivity, boolean live) {}

  private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
  private static final String TITLE_MARKER = "\"ai-title\"";

  private final TranscriptLocator locator;
  private final WatcherProperties props;
  private final ObjectMapper mapper;
  private final StateStream<List<Entry>> stream = new StateStream<>();

  /** Titles Claude Code writes into the transcript, captured by the tail as it goes past. */
  private final Map<String, String> titles = new ConcurrentHashMap<>();

  /** Sessions whose transcript has already been searched for a title it wrote before we started. */
  private final KeySetView<String, Boolean> searched = ConcurrentHashMap.newKeySet();

  public SessionRegistry(TranscriptLocator locator, WatcherProperties props, ObjectMapper mapper) {
    this.locator = locator;
    this.props = props;
    this.mapper = mapper;
    stream.publish(List.of());
  }

  public List<Entry> current() {
    return stream.current();
  }

  public StateStream<List<Entry>> stream() {
    return stream;
  }

  void recordTitle(String sessionId, String title) {
    if (sessionId != null && title != null && !title.isBlank()) {
      titles.put(sessionId, title);
    }
  }

  /**
   * The title for a session, looked up in its transcript the first time the session is seen.
   *
   * <p>The tail cannot supply this on its own. It deliberately skips whatever a transcript already
   * contained when the watcher started - that is history, not activity - and Claude Code writes the
   * title near the beginning of a session. Without this, every session that predates the watcher
   * would show as an opaque identifier forever.
   *
   * <p>Searched once per session. If nothing is found the session may still be titled later, and
   * the tail will pick that up as it goes past.
   */
  private String titleFor(String id, Path transcript) {
    String known = titles.get(id);
    if (known != null || !searched.add(id)) {
      return known;
    }
    try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
      lines
          .filter(line -> line.contains(TITLE_MARKER))
          .findFirst()
          .ifPresent(
              line -> {
                try {
                  recordTitle(id, mapper.readTree(line).path("aiTitle").asString(null));
                } catch (RuntimeException e) {
                  log.debug("cannot read title for {}: {}", id, e.toString());
                }
              });
    } catch (IOException | RuntimeException e) {
      log.debug("cannot search {}: {}", transcript, e.toString());
    }
    return titles.get(id);
  }

  @Scheduled(fixedDelayString = "${watcher.registry-poll-ms:2000}")
  public void scan() {
    Instant liveSince = Instant.now().minus(LIVE_WINDOW);

    // Sorted and trimmed before any title is read, so the work is bounded by what is shown rather
    // than by how long the project has existed.
    record Candidate(String id, Path file, Instant modified) {}
    List<Candidate> candidates = new ArrayList<>();
    for (Path transcript : locator.transcripts()) {
      String name = transcript.getFileName().toString();
      try {
        candidates.add(
            new Candidate(
                name.substring(0, name.length() - ".jsonl".length()),
                transcript,
                Files.getLastModifiedTime(transcript).toInstant()));
      } catch (IOException e) {
        // A session that vanished between listing and reading simply is not there.
      }
    }
    candidates.sort(Comparator.comparing(Candidate::modified).reversed());

    List<Entry> entries =
        candidates.stream()
            .limit(props.getMaxSessions())
            .map(
                c ->
                    new Entry(
                        c.id(),
                        titleFor(c.id(), c.file()),
                        c.modified().toString(),
                        c.modified().isAfter(liveSince)))
            .toList();

    if (!entries.equals(stream.current())) {
      stream.publish(entries);
    }
  }
}
