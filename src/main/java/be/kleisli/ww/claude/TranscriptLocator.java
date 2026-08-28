package be.kleisli.ww.claude;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
      // The exact directory, plus any session started in a subdirectory of the workspace.
      return dirs.filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith(prefix))
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

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
}
