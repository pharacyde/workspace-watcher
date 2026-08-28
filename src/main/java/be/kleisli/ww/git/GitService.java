package be.kleisli.ww.git;

import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Git state for the workspace.
 *
 * <p>Shells out to {@code git} rather than embedding JGit: it is faster on large repositories, it
 * honours the user's own git config and hooks, and it cannot drift from what the developer sees in
 * their own terminal.
 */
@Service
public class GitService {

  private static final Logger log = LoggerFactory.getLogger(GitService.class);

  public record FileStatus(String path, String status, boolean staged) {}

  public record Snapshot(
      boolean repo, String branch, String head, String headSubject, List<FileStatus> files) {}

  private static final Snapshot NOT_A_REPO = new Snapshot(false, null, null, null, List.of());

  private final WatcherProperties props;
  private final StateStream<Snapshot> stream = new StateStream<>();
  private volatile Snapshot last = NOT_A_REPO;

  public GitService(WatcherProperties props) {
    this.props = props;
    stream.publish(last);
  }

  public Snapshot current() {
    return last;
  }

  public StateStream<Snapshot> stream() {
    return stream;
  }

  /** Recomputes the working tree and broadcasts it only when something actually changed. */
  public synchronized void refresh() {
    Snapshot snapshot = read();
    if (snapshot.equals(last)) {
      return;
    }
    last = snapshot;
    stream.publish(snapshot);
  }

  private Snapshot read() {
    Path ws = props.workspacePath();
    if (!Files.isDirectory(ws)) {
      return NOT_A_REPO;
    }
    Shell.Result inside = Shell.run(ws, List.of("git", "rev-parse", "--is-inside-work-tree"), 5);
    if (!inside.ok() || !inside.stdout().strip().equals("true")) {
      return NOT_A_REPO;
    }

    String branch = Shell.run(ws, List.of("git", "branch", "--show-current"), 5).stdout().strip();
    String head = Shell.run(ws, List.of("git", "rev-parse", "--short", "HEAD"), 5).stdout().strip();
    String subject = Shell.run(ws, List.of("git", "log", "-1", "--format=%s"), 5).stdout().strip();

    List<FileStatus> files = new ArrayList<>();
    Shell.Result status =
        Shell.run(ws, List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), 15);
    for (String line : status.lines()) {
      if (line.length() < 4) {
        continue;
      }
      char index = line.charAt(0);
      char worktree = line.charAt(1);
      String path = line.substring(3);
      // Renames are reported as "old -> new"; the new path is the one worth showing.
      int arrow = path.indexOf(" -> ");
      if (arrow >= 0) {
        path = path.substring(arrow + 4);
      }
      files.add(new FileStatus(path, describe(index, worktree), index != ' ' && index != '?'));
    }
    return new Snapshot(true, branch.isEmpty() ? "(detached)" : branch, head, subject, files);
  }

  private static String describe(char index, char worktree) {
    if (index == '?') return "untracked";
    if (index == 'A' || worktree == 'A') return "added";
    if (index == 'D' || worktree == 'D') return "deleted";
    if (index == 'R') return "renamed";
    return "modified";
  }

  public record Versions(
      String path, String head, String working, boolean binary, boolean tooLarge) {}

  /**
   * Both sides of a file, for a side-by-side editor.
   *
   * <p>Reads the working copy from disk rather than from {@code git}, so the view reflects what an
   * agent wrote a moment ago even before anything is staged.
   */
  public Versions versions(String relativePath) {
    Path workspace = props.workspacePath();
    Path file = workspace.resolve(relativePath).normalize();

    String head = Shell.run(workspace, List.of("git", "show", "HEAD:" + relativePath), 15).stdout();

    String working = "";
    boolean binary = false;
    boolean tooLarge = false;
    try {
      if (Files.isRegularFile(file)) {
        if (Files.size(file) > props.getMaxDiffBytes()) {
          tooLarge = true;
        } else {
          byte[] bytes = Files.readAllBytes(file);
          // A NUL byte is what git itself uses to decide a file is binary.
          for (byte b : bytes) {
            if (b == 0) {
              binary = true;
              break;
            }
          }
          if (!binary) {
            working = new String(bytes, StandardCharsets.UTF_8);
          }
        }
      }
    } catch (IOException e) {
      log.debug("cannot read {}: {}", file, e.toString());
    }

    if (binary || tooLarge) {
      return new Versions(relativePath, "", "", binary, tooLarge);
    }
    return new Versions(relativePath, head, working, false, false);
  }

  /** Unified diff for one path, staged and unstaged combined. Empty for untracked files. */
  public Map<String, String> diff(String relativePath) {
    Path ws = props.workspacePath();
    Map<String, String> result = new LinkedHashMap<>();
    result.put("path", relativePath);
    result.put("unstaged", Shell.run(ws, List.of("git", "diff", "--", relativePath), 15).stdout());
    result.put(
        "staged",
        Shell.run(ws, List.of("git", "diff", "--cached", "--", relativePath), 15).stdout());
    if (result.get("unstaged").isBlank() && result.get("staged").isBlank()) {
      // Untracked: show the file as if every line were added.
      Shell.Result untracked =
          Shell.run(ws, List.of("git", "diff", "--no-index", "--", "/dev/null", relativePath), 15);
      result.put("unstaged", untracked.stdout());
    }
    return result;
  }
}
