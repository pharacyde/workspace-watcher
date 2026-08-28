package be.kleisli.ww.git;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

  private final ActiveWorkspace active;
  private final WatcherProperties props;
  private final StateStream<Snapshot> stream = new StateStream<>();

  /**
   * Repository root, which is not necessarily the workspace.
   *
   * <p>Every path git reports is relative to this, never to the workspace, so every path git is
   * handed must be too.
   */
  private volatile Path root;

  public GitService(ActiveWorkspace active, WatcherProperties props) {
    this.active = active;
    this.props = props;
    stream.publish(NOT_A_REPO);
  }

  public Snapshot current() {
    return stream.current();
  }

  public StateStream<Snapshot> stream() {
    return stream;
  }

  /** Recomputes the working tree and broadcasts it only when something actually changed. */
  public synchronized void refresh() {
    Snapshot snapshot = read();
    if (snapshot.equals(stream.current())) {
      return;
    }
    stream.publish(snapshot);
  }

  /**
   * Resolves a git-reported path to a real file, and rejects anything outside the repository.
   *
   * <p>Resolved against the repository root rather than the workspace. {@code git status
   * --porcelain} emits repository-root-relative paths whatever directory it runs in, so resolving
   * them against a workspace that sits in a subdirectory silently points at a file that does not
   * exist.
   */
  public Path resolveInRepo(String relativePath) {
    Path repoRoot = root;
    if (repoRoot == null) {
      throw new IllegalStateException("no workspace is being watched");
    }
    Path resolved = repoRoot.resolve(relativePath).normalize();
    if (!resolved.startsWith(repoRoot)) {
      throw new IllegalArgumentException("path outside repository");
    }
    return resolved;
  }

  private Snapshot read() {
    Path ws = active.get();
    if (ws == null || !Files.isDirectory(ws)) {
      root = null;
      return NOT_A_REPO;
    }
    Shell.Result toplevel = Shell.run(ws, List.of("git", "rev-parse", "--show-toplevel"), 5);
    if (!toplevel.ok() || toplevel.stdout().isBlank()) {
      root = ws;
      return NOT_A_REPO;
    }
    root = Path.of(toplevel.stdout().strip()).toAbsolutePath().normalize();

    String branch = Shell.run(ws, List.of("git", "branch", "--show-current"), 5).stdout().strip();
    String head = Shell.run(ws, List.of("git", "rev-parse", "--short", "HEAD"), 5).stdout().strip();
    String subject = Shell.run(ws, List.of("git", "log", "-1", "--format=%s"), 5).stdout().strip();

    List<FileStatus> files = new ArrayList<>();
    // Scoped to the workspace with "-- ." so observing a subdirectory does not list the whole
    // repository. Paths come back repository-root-relative regardless; see resolveInRepo.
    Shell.Result status =
        Shell.run(
            ws, List.of("git", "status", "--porcelain=v1", "--untracked-files=all", "--", "."), 15);
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
      // A submodule appears as a single entry whose path is a directory. Labelling it "modified"
      // would invite a click that tries to diff a directory.
      String state =
          Files.isDirectory(root.resolve(path)) ? "submodule" : describe(index, worktree);
      files.add(new FileStatus(path, state, index != ' ' && index != '?'));
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

  /**
   * The repository that actually tracks a file, which is not always the one being watched.
   *
   * <p>A submodule is a repository of its own nested inside another. The superproject records only
   * its commit, so anything about the files inside it has to be asked of the submodule.
   */
  private Path repositoryOwning(Path file) {
    Path directory = Files.isDirectory(file) ? file : file.getParent();
    if (directory == null) {
      return root;
    }
    Shell.Result result = Shell.run(directory, List.of("git", "rev-parse", "--show-toplevel"), 5);
    if (!result.ok() || result.stdout().isBlank()) {
      return root;
    }
    return Path.of(result.stdout().strip()).toAbsolutePath().normalize();
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
    Path file = resolveInRepo(relativePath);

    // A file inside a submodule lives in that submodule's own object store, so asking the
    // superproject for it fails with "exists on disk, but not in HEAD" and the whole file would
    // render as newly added. Ask whichever repository actually owns the file.
    Path owner = repositoryOwning(file);
    String pathInOwner = owner.equals(root) ? relativePath : owner.relativize(file).toString();

    // Run from that repository root: "HEAD:<path>" is resolved from there, not from the cwd.
    String head = Shell.run(owner, List.of("git", "show", "HEAD:" + pathInOwner), 15).stdout();

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
}
