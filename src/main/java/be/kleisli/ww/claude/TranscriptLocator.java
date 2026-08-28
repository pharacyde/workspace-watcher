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
   * Subagent transcripts, which live one directory level deeper.
   *
   * <p>When Claude Code delegates to a subagent, that agent's work does not go into the session
   * transcript at all - it gets its own file under {@code <session-id>/subagents/agent-<id>.jsonl}.
   * A glob over the project directory alone therefore misses it entirely, and with it most of what
   * a delegating session actually did: measured here, the subagent files hold thousands of Bash,
   * Read, Edit and Write calls that were invisible to this watcher.
   *
   * <p>The records carry the parent's {@code sessionId}, so a subagent shows up under the session
   * that spawned it rather than as a session of its own.
   */
  public List<Path> subagentTranscripts() {
    Instant since = Instant.now().minus(SUBAGENT_WINDOW);
    List<Path> found = new ArrayList<>();
    for (Path dir : directories()) {
      try (Stream<Path> sessions = Files.list(dir)) {
        for (Path session : sessions.filter(Files::isDirectory).toList()) {
          collectSubagents(session.resolve("subagents"), since, found);
        }
      } catch (IOException e) {
        // A directory that vanished between listing and reading simply is not there.
      }
    }
    return found;
  }

  private static void collectSubagents(Path subagents, Instant since, List<Path> found) {
    if (!Files.isDirectory(subagents)) {
      return;
    }
    try (Stream<Path> files = Files.list(subagents)) {
      files
          .filter(f -> f.getFileName().toString().endsWith(".jsonl"))
          .filter(f -> modifiedAfter(f, since))
          .forEach(found::add);
    } catch (IOException e) {
      // Same: a race with the writer is not an error worth reporting.
    }
  }

  private static boolean modifiedAfter(Path file, Instant since) {
    try {
      return Files.getLastModifiedTime(file).toInstant().isAfter(since);
    } catch (IOException e) {
      return false;
    }
  }

  /** Everything the tail should follow: the sessions themselves plus their live subagents. */
  public List<Path> allTranscripts() {
    List<Path> all = new ArrayList<>(transcripts());
    all.addAll(subagentTranscripts());
    return all;
  }
}
