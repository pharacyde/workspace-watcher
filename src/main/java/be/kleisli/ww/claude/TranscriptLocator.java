package be.kleisli.ww.claude;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Finds the transcript files belonging to the workspace being watched.
 *
 * <p>Shared by the tail and the session register so there is one definition of where a workspace's
 * transcripts live, rather than two that can drift apart.
 */
@Component
public class TranscriptLocator {

  /**
   * How recently a subagent transcript must have been written to be worth tailing.
   *
   * <p>Subagent transcripts are never deleted, so a long-lived project accumulates them without
   * bound - over a thousand on this machine. Reopening every one of them twice a second to check
   * for growth would cost far more than it can ever return: a subagent that has not been written to
   * in hours has finished, and a finished agent produces no further activity.
   */
  private static final Duration SUBAGENT_WINDOW = Duration.ofHours(2);

  /**
   * How long a directory listing is reused before it is taken again.
   *
   * <p>Finding the subagent directories means listing every session directory of the workspace, and
   * that set only changes when a session starts. Doing it on every 500 ms poll cost hundreds of
   * stat calls a second on a project with a long history, to rediscover exactly what was there
   * before. The files inside are still checked every poll; only the search for the directories is
   * held.
   */
  private static final Duration DISCOVERY_TTL = Duration.ofSeconds(5);

  private record Discovery(Path workspace, Instant taken, List<Path> subagentDirs) {}

  private final AtomicReference<Discovery> discovery = new AtomicReference<>();

  private final WatcherProperties props;
  private final ActiveWorkspace active;

  public TranscriptLocator(WatcherProperties props, ActiveWorkspace active) {
    this.props = props;
    this.active = active;
  }

  /**
   * Claude Code derives the transcript directory name from the working directory by replacing every
   * character that is not a letter or digit with a dash.
   */
  public static String escapeCwd(Path path) {
    return path.toString().replaceAll("[^a-zA-Z0-9]", "-");
  }

  public List<Path> directories() {
    Path workspace = active.get();
    Path projects = props.claudeProjectsPath();
    if (workspace == null || !Files.isDirectory(projects)) {
      return List.of();
    }
    String prefix = escapeCwd(workspace);
    try (Stream<Path> dirs = Files.list(projects)) {
      // The exact directory, plus any session started in a subdirectory of the workspace. The
      // boundary matters: a bare prefix test also matches a sibling, so watching /Users/me/Dev
      // would pull in every session from /Users/me/Dev2 as well.
      return dirs.filter(Files::isDirectory)
          .filter(
              dir -> {
                String name = dir.getFileName().toString();
                return name.equals(prefix) || name.startsWith(prefix + "-");
              })
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Session transcripts: one file per session, directly in the project directory. */
  public List<Path> transcripts() {
    return directories().stream()
        .flatMap(
            dir -> {
              try (Stream<Path> files = Files.list(dir)) {
                return files.filter(f -> f.toString().endsWith(".jsonl")).toList().stream();
              } catch (IOException e) {
                return Stream.empty();
              }
            })
        .toList();
  }

  /**
   * The {@code subagents} directories of this workspace, one per session that delegated.
   *
   * <p>Cached for {@link #DISCOVERY_TTL}: the answer changes only when a session starts, and the
   * search is the expensive half of finding a subagent transcript.
   */
  private List<Path> subagentDirs() {
    Path workspace = active.get();
    Discovery known = discovery.get();
    if (known != null
        && known.taken().isAfter(Instant.now().minus(DISCOVERY_TTL))
        && java.util.Objects.equals(known.workspace(), workspace)) {
      return known.subagentDirs();
    }
    List<Path> dirs = new ArrayList<>();
    for (Path dir : directories()) {
      try (Stream<Path> sessions = Files.list(dir)) {
        sessions
            .map(session -> session.resolve("subagents"))
            .filter(Files::isDirectory)
            .forEach(dirs::add);
      } catch (IOException e) {
        // A directory that vanished between listing and reading simply is not there.
      }
    }
    discovery.set(new Discovery(workspace, Instant.now(), List.copyOf(dirs)));
    return dirs;
  }

  /**
   * Subagent transcripts, which live one directory level deeper.
   *
   * <p>When Claude Code delegates to a subagent, that agent's work does not go into the session
   * transcript at all - it gets its own file under {@code <session-id>/subagents/agent-<id>.jsonl}.
   * A glob over the project directory alone therefore misses it entirely, and with it most of what
   * a delegating session actually did: measured here, the subagent files hold thousands of Bash,
   * Read, Edit and Write calls that were invisible to this watcher, and 5.5% of the tokens spent.
   *
   * <p>The records carry the parent's {@code sessionId}, so a subagent shows up under the session
   * that spawned it rather than as a session of its own.
   */
  public List<Path> subagentTranscripts() {
    List<Path> found = new ArrayList<>();
    for (Path dir : subagentDirs()) {
      try (Stream<Path> files = Files.list(dir)) {
        files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).forEach(found::add);
      } catch (IOException e) {
        // Same: a race with the writer is not an error worth reporting.
      }
    }
    return found;
  }

  /**
   * The session a subagent transcript belongs to, taken from the directory it sits in.
   *
   * <p>The records inside say the same thing, but the path already knows and reading a 24 MB file
   * to learn it would not.
   */
  public static String sessionOf(Path subagentTranscript) {
    Path session = subagentTranscript.getParent();
    return session == null || session.getParent() == null
        ? null
        : session.getParent().getFileName().toString();
  }

  /**
   * Everything the tail should follow: the sessions, plus the subagents still being written to.
   *
   * <p>Subagent transcripts are never cleaned up - over a thousand on this machine - and one that
   * has been untouched for hours has finished. Opening those on every poll would buy nothing.
   */
  public List<Path> allTranscripts() {
    Instant since = Instant.now().minus(SUBAGENT_WINDOW);
    List<Path> all = new ArrayList<>(transcripts());
    subagentTranscripts().stream().filter(f -> modifiedAfter(f, since)).forEach(all::add);
    return all;
  }

  /**
   * Every transcript there is, however old.
   *
   * <p>What was spent is history and does not go stale, so the recency window that bounds the tail
   * would only make the total wrong here.
   */
  public List<Path> everyTranscript() {
    List<Path> all = new ArrayList<>(transcripts());
    all.addAll(subagentTranscripts());
    return all;
  }

  private static boolean modifiedAfter(Path file, Instant since) {
    try {
      return Files.getLastModifiedTime(file).toInstant().isAfter(since);
    } catch (IOException e) {
      return false;
    }
  }
}
