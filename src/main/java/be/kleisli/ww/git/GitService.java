package be.kleisli.ww.git;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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
    gitStamp = gitStamp();
    Snapshot snapshot = read();
    if (snapshot.equals(stream.current())) {
      return;
    }
    stream.publish(snapshot);
  }

  /**
   * Refreshes when git's own state moved without any watched file moving.
   *
   * <p>A commit, a checkout, a stash or a branch switch rewrites the index and HEAD and leaves
   * every file in the tree exactly as it was, so the scanner sees nothing to report and the panel
   * kept describing the working tree from before the commit - for as long as nobody touched a file
   * afterwards, and a page reload does not help because the stale snapshot is the server's.
   *
   * <p>Two stat calls rather than a {@code git status} on every scan: that command is the expensive
   * one on a large repository, which is exactly why the scan does not simply always refresh. A few
   * more for the submodules, measured at 0.15ms for the eighteen of a real superproject.
   */
  public synchronized void refreshIfGitChanged() {
    String stamp = gitStamp();
    if (stamp == null || stamp.equals(gitStamp)) {
      return;
    }
    refresh();
  }

  /** Last seen state of git's own files; null until the first {@link #refresh()}. */
  private volatile String gitStamp;

  /**
   * What git's own state last looked like, for anything that has to notice a commit.
   *
   * <p>Read rather than recomputed, so a caller polling this costs nothing at all.
   */
  public String stamp() {
    return gitStamp;
  }

  /**
   * The index and HEAD, by size and modification time.
   *
   * <p>Those two are what {@code git status} reads besides the tree itself: the index changes on a
   * commit, an add and a stash, HEAD on a checkout or a branch switch. Size as well as time,
   * because two commits inside one filesystem timestamp are not exotic on a coarse filesystem.
   */
  private String gitStamp() {
    Path repoRoot = root;
    if (repoRoot == null) {
      return null;
    }
    Path gitDir = repoRoot.resolve(".git");
    try {
      // In a linked worktree .git is a file naming the real directory, which is where the index
      // for that worktree lives; stamping the file itself would never change.
      if (Files.isRegularFile(gitDir)) {
        String pointer = Files.readString(gitDir, StandardCharsets.UTF_8).strip();
        if (!pointer.startsWith("gitdir:")) {
          return null;
        }
        gitDir = repoRoot.resolve(pointer.substring("gitdir:".length()).strip()).normalize();
      }
      // A linked worktree has its own index and HEAD but shares everything else, and submodule
      // gitdirs are part of that everything else: they stay under the main .git/modules. Stamping
      // <worktree gitdir>/modules would find nothing, and the stale panel this exists to fix would
      // come straight back for anyone working in a worktree. "commondir" is where git itself
      // records the way back.
      Path common = gitDir;
      Path commondir = gitDir.resolve("commondir");
      if (Files.isRegularFile(commondir)) {
        common =
            gitDir.resolve(Files.readString(commondir, StandardCharsets.UTF_8).strip()).normalize();
      }
      StringBuilder stamp =
          new StringBuilder(stampOf(gitDir.resolve("index")))
              .append("/")
              .append(stampOf(gitDir.resolve("HEAD")));
      // A commit inside a submodule leaves the superproject's index and HEAD byte-identical -
      // measured, both mtimes unchanged across one - so without this the panel went stale for
      // exactly as long as the work stayed inside the submodule. The file that moves is the
      // submodule's own index; its HEAD does not, because it holds "ref: refs/heads/<branch>".
      stampSubmodules(common.resolve("modules"), stamp, 0);
      return stamp.toString();
    } catch (IOException | RuntimeException e) {
      // Fail quiet: this is an optimisation over refreshing, and a repository we cannot stat is
      // one the refresh below will describe just as well.
      log.debug("cannot stamp git state at {}", gitDir, e);
      return null;
    }
  }

  /**
   * Stamps the index of every submodule under a {@code modules} directory.
   *
   * <p>Walks by hand rather than with {@link Files#walk}: a module directory holds the whole object
   * store, and walking into that to find one file beside it would be thousands of stat calls for
   * the sake of one. A directory that has an index is a repository and is not descended into,
   * except for its own nested {@code modules}; one that has none is a path-shaped module name like
   * {@code libs/inner} and is.
   */
  private static void stampSubmodules(Path modules, StringBuilder stamp, int depth)
      throws IOException {
    if (depth > MAX_SUBMODULE_DEPTH || !Files.isDirectory(modules)) {
      return;
    }
    List<Path> children;
    try (var entries = Files.list(modules)) {
      // Sorted, because two runs that stamp the same repositories in a different order would
      // compare unequal and refresh forever.
      children = entries.filter(Files::isDirectory).sorted().toList();
    } catch (IOException | RuntimeException e) {
      // A module directory that vanishes mid-iteration - a deinit, a clone in progress - used to
      // take the whole stamp down with it, and a null stamp means refreshIfGitChanged does
      // nothing at all: one unreadable module would stop the panel noticing any commit anywhere.
      // A sentinel keeps the stamp a value, and one that differs from the readable case.
      stamp.append("/?").append(modules.getFileName());
      return;
    }
    for (Path child : children) {
      Path index = child.resolve("index");
      try {
        if (Files.exists(index)) {
          // Inside the try as well: stampOf stats a file it has just seen exist, and a module
          // removed between the two calls threw out of here into gitStamp's catch, which returns
          // null - and a null stamp makes refreshIfGitChanged do nothing at all. That is the same
          // "one unreadable module stops the panel noticing any commit anywhere" the sentinel
          // above exists to prevent, reached by the other path.
          stamp.append("/").append(child.getFileName()).append(":").append(stampOf(index));
          stampSubmodules(child.resolve("modules"), stamp, depth + 1);
        } else {
          stampSubmodules(child, stamp, depth + 1);
        }
      } catch (IOException | RuntimeException e) {
        stamp.append("/?").append(child.getFileName());
      }
    }
  }

  private static String stampOf(Path file) throws IOException {
    if (!Files.exists(file)) {
      return "-";
    }
    return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
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
    if (!resolved.startsWith(repoRoot) || escapesBySymlink(repoRoot, resolved)) {
      throw new IllegalArgumentException("path outside repository");
    }
    return resolved;
  }

  /**
   * Whether a path that is lexically inside the repository leaves it on disk.
   *
   * <p>{@code normalize()} is purely lexical: with {@code link -> /somewhere/else} in the
   * repository, {@code link/secret.txt} normalizes to itself, starts with the root, and was read
   * and served - measured, on a server that has no authentication precisely because everything it
   * serves is meant to be inside the workspace. The listing side already refuses to descend into a
   * symlink; this is the fetch side, which a caller reaches without the listing.
   *
   * <p>The nearest existing ancestor rather than the path itself, because a deleted file is a
   * normal thing to ask for here and has no real path at all.
   */
  private static boolean escapesBySymlink(Path repoRoot, Path resolved) {
    try {
      Path existing = resolved;
      while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        existing = existing.getParent();
        if (existing == null) {
          return false;
        }
      }
      // The parent of a symlink, so that asking for the link itself is still allowed: it is inside
      // the repository, and what it resolves to is the caller's business only once it is read.
      Path real =
          Files.isSymbolicLink(existing)
              ? existing.getParent().toRealPath()
              : existing.toRealPath();
      return !real.startsWith(repoRoot.toRealPath());
    } catch (IOException e) {
      // Cannot tell, so do not claim it is safe.
      log.debug("cannot real-path {}: {}", resolved, e.toString());
      return true;
    }
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
    collectStatus(ws, "", files, 0);
    return new Snapshot(true, branch.isEmpty() ? "(detached)" : branch, head, subject, files);
  }

  /**
   * The working tree of one repository, and of any submodule of it that has something to show.
   *
   * <p>Git collapses a submodule into a single gitlink entry with a directory path, and there is no
   * flag that makes it do otherwise - {@code --ignore-submodules=none} changes nothing. On a
   * superproject that is the whole answer: one line for eighteen repositories, and a panel of
   * dead-end rows for a project where all the real work happens inside them.
   *
   * <p>So each dirty submodule is asked itself. Only the dirty ones, because the status just read
   * is already the list of which those are: measured on a real superproject, nothing dirty costs
   * nothing extra, one dirty costs 20ms on top of 224ms. The rejected alternative was {@code
   * --ignore-submodules=dirty} (14ms) and then descending into all eighteen unconditionally, which
   * is 556ms every time - and wrong as a gate besides, because that flag reports nothing at all for
   * a submodule that is merely dirty rather than moved.
   *
   * @param prefix where this repository sits in the superproject, so paths stay root-relative
   */
  private void collectStatus(Path directory, String prefix, List<FileStatus> files, int depth) {
    // Scoped to the workspace with "-- ." so observing a subdirectory does not list the whole
    // repository. Paths come back repository-root-relative regardless; see resolveInRepo.
    //
    // --no-optional-locks because plain `git status` refreshes the index and writes it back, and
    // that write takes .git/index.lock - measured, the index mtime moves on the first status
    // after an edit. An agent running `git add` or `git commit` in the same repository at that
    // moment fails outright with "Unable to create '.git/index.lock': File exists", which is
    // invariant 1 broken by the observer. The flag tells git to do the read-only thing instead.
    Shell.Result status =
        Shell.run(
            directory,
            List.of(
                "git",
                "--no-optional-locks",
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--",
                "."),
            15);
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
      // An untracked nested repository is reported with a trailing slash - "?? tool/" - which
      // would otherwise become "tool//b.txt" once it is used as a prefix.
      if (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      path = prefix + path;
      Path resolved = root.resolve(path);
      // A submodule appears as a single entry whose path is a directory. Labelling it "modified"
      // would invite a click that tries to diff a directory. NOFOLLOW_LINKS, because
      // Files.isDirectory follows a symlink: a link to a directory outside the repository read as
      // a submodule, and this then listed and served what was inside it - measured, on a server
      // that has no authentication because everything it serves is meant to be inside the
      // workspace.
      boolean isDir = Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS);
      // A directory entry is only a repository when it has a .git. Without that test, a tracked
      // file replaced on disk by a directory - git reports " D foo", so the index column is a
      // space and reads as tracked - was labelled a submodule and descended into. The descent ran
      // git status in a directory that is not a repository root, where porcelain paths still come
      // back relative to the real root, so every file under it was listed a second time under a
      // doubled prefix: "foo/foo/bar.txt", a row that diffs to two empty panes. Measured.
      boolean isRepo = isDir && Files.exists(resolved.resolve(".git"));
      boolean tracked = index != '?';
      // Three things here are not files to click, and each is a different sentence to say. A
      // tracked directory entry is a submodule of this project. An untracked one is a repository
      // of its own that this project does not know about - a vendored clone, someone's scratch
      // checkout - and git reports it as a single entry precisely because it will not look inside.
      // And a symlink is neither: it has no content of its own to diff, and calling it "untracked"
      // is what made clicking one open two empty panes with no explanation.
      String state =
          isRepo
              ? (tracked ? "submodule" : "nested")
              : Files.isSymbolicLink(resolved) ? "symlink" : describe(index, worktree);
      files.add(new FileStatus(path, state, index != ' ' && index != '?'));
      // Only into a tracked one. An untracked nested checkout - a vendored clone, someone's
      // scratch repository - is not a submodule of this project, and descending into every one of
      // them would be an unbounded number of `git status` calls per refresh rather than the "one
      // per dirty submodule" this is costed at.
      if (isRepo && tracked && depth < MAX_SUBMODULE_DEPTH) {
        // Right after its own row rather than at the end: the row says the recorded commit moved,
        // the rows under it say what moved it, and those are one thing to read rather than two.
        collectStatus(resolved, path + "/", files, depth + 1);
      }
    }
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

  /**
   * Two sides of a file, and which two revisions they actually are.
   *
   * <p>{@code head} and {@code working} keep their names because the common answer is still HEAD
   * against the working copy, but they are the left and the right side of whatever pair {@code
   * leftRev} and {@code rightRev} name. {@code live} is true only when the right side is the file
   * on disk, so a caller can tell a diff that will keep changing from one that is finished.
   */
  public record Versions(
      String path,
      String head,
      String working,
      boolean binary,
      boolean tooLarge,
      String leftRev,
      String rightRev,
      boolean live) {}

  /** Deep enough for nested submodules with path-shaped names, shallow enough to stay bounded. */
  private static final int MAX_SUBMODULE_DEPTH = 6;

  /** What the two sides are named when the right one is the file on disk. */
  private static final String HEAD = "HEAD";

  private static final String WORKING_COPY = "working copy";

  /**
   * A revision as git will accept it, and never an option.
   *
   * <p>Arguments are passed as a list rather than through a shell, so there is nothing to quote;
   * what this stops is a value beginning with {@code -} being read as a flag by git itself.
   */
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][\\w.@/~^{}-]*");

  /**
   * Both sides of a file, for a side-by-side editor.
   *
   * <p>Reads the working copy from disk rather than from {@code git}, so the view reflects what an
   * agent wrote a moment ago even before anything is staged.
   */
  public Versions versions(String relativePath) {
    return versions(relativePath, null);
  }

  /**
   * Both sides of a file, either uncommitted work or one commit.
   *
   * <p>With no revision the right side is the working copy. That was the only answer this could
   * give, and it is empty for exactly as long as it matters most: the moment you commit, both sides
   * become identical and an hour of work disappears from the panel. So when nothing is uncommitted,
   * the pair falls back to the last commit that touched the file - a real diff instead of a blank
   * one, and {@code leftRev}/{@code rightRev} say which, because a commit labelled "live" would be
   * the kind of confident untruth this project exists to avoid.
   */
  public Versions versions(String relativePath, String rev) {
    if (rev != null && !REVISION.matcher(rev).matches()) {
      throw new IllegalArgumentException("not a revision: " + rev);
    }
    Path file = resolveInRepo(relativePath);

    // A file inside a submodule lives in that submodule's own object store, so asking the
    // superproject for it fails with "exists on disk, but not in HEAD" and the whole file would
    // render as newly added. Ask whichever repository actually owns the file.
    Path owner = repositoryOwning(file);
    String pathInOwner = owner.equals(root) ? relativePath : owner.relativize(file).toString();

    if (rev != null) {
      return versionsAt(owner, relativePath, pathInOwner, rev);
    }

    // Run from that repository root: "HEAD:<path>" is resolved from there, not from the cwd.
    //
    // Through the same size-checked reader the commit sides use. This side was read unbounded
    // while the working copy right beside it was stat-ed first, so a committed blob too big to
    // show - a vendored bundle, a checked-in dataset - was that many bytes on the heap before
    // anything decided it was not going to be shown. It also stops a git that never answered from
    // being read as "absent at HEAD", which renders the file as newly added: an attribution
    // invented out of a process that failed to run.
    Side atHead = side(owner, HEAD, pathInOwner);
    String head = atHead.text();

    String working = "";
    boolean binary = isBinary(head);
    boolean tooLarge = atHead.tooLarge();
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
      return new Versions(relativePath, "", "", binary, tooLarge, HEAD, WORKING_COPY, true);
    }
    if (head.equals(working)) {
      // Nothing uncommitted, so this pair would be two identical sides - the blank panel you get
      // the moment you commit. Show the commit that last touched the file instead. That costs a
      // `git log` plus a size check and a read per side, and it is worth being precise about the
      // count: it is only ever paid on a query someone asked for, never on a poll.
      Versions committed = lastCommitTouching(owner, relativePath, pathInOwner);
      if (committed != null) {
        return committed;
      }
    }
    return new Versions(relativePath, head, working, false, false, HEAD, WORKING_COPY, true);
  }

  /** The most recent commit that changed this file, or null when git knows of none. */
  private Versions lastCommitTouching(Path owner, String relativePath, String pathInOwner) {
    // "%h %p" in one call: the short hash and its parents, which is the pair the diff needs.
    String hashes =
        Shell.run(owner, List.of("git", "log", "-1", "--format=%h %p", "--", pathInOwner), 15)
            .stdout()
            .strip();
    if (hashes.isBlank()) {
      return null;
    }
    return atCommit(owner, relativePath, pathInOwner, hashes);
  }

  /** One named revision, resolved to a commit and its parent. */
  private Versions versionsAt(Path owner, String relativePath, String pathInOwner, String rev) {
    // The trailing "--" is what makes git read the argument as a revision and nothing else.
    // Without it a value that happens to name a file or a directory - "docs", "README.md" - is
    // accepted as a pathspec, and the answer is the last commit that touched *that*, reported
    // with a real hash beside two empty sides. Measured: `git log -1 docs` exits 0 here.
    String hashes =
        Shell.run(owner, List.of("git", "log", "-1", "--format=%h %p", rev, "--"), 15)
            .stdout()
            .strip();
    if (hashes.isBlank()) {
      throw new IllegalArgumentException("no such revision: " + rev);
    }
    return atCommit(owner, relativePath, pathInOwner, hashes);
  }

  /**
   * Both sides of one commit: the file as its parent had it, against the file as that commit left
   * it.
   *
   * <p>A side that revision did not have is empty, which is the same answer for "added in this
   * commit", "deleted in this commit" and "this is the root commit" - each of which is exactly an
   * empty side rather than an error.
   *
   * @param hashes the commit and its parents, as {@code %h %p} printed them
   */
  private Versions atCommit(Path owner, String relativePath, String pathInOwner, String hashes) {
    String[] parts = hashes.split(" ");
    String commit = parts[0];
    // A merge lists several parents; the first is the branch it was merged into, which is the one
    // that makes "what this commit changed" mean what a reader expects.
    String parent = parts.length > 1 ? parts[1] : "";

    Side right = side(owner, commit, pathInOwner);
    Side left = parent.isEmpty() ? Side.ABSENT : side(owner, parent, pathInOwner);

    if (left.tooLarge() || right.tooLarge()) {
      return new Versions(relativePath, "", "", false, true, parent, commit, false);
    }
    if (isBinary(left.text()) || isBinary(right.text())) {
      return new Versions(relativePath, "", "", true, false, parent, commit, false);
    }
    return new Versions(
        relativePath, left.text(), right.text(), false, false, parent, commit, false);
  }

  private record Side(String text, boolean tooLarge) {
    static final Side ABSENT = new Side("", false);
  }

  /**
   * One side out of the object store, without reading a blob too big to show.
   *
   * <p>The size is asked before the content, for the same reason the working copy is stat-ed before
   * it is read: {@code git show} of a large committed blob - a vendored bundle, a checked-in
   * dataset - would be that many bytes on the heap before anything decided they were not going to
   * be shown. It costs one more process per side, and only on a query someone asked for; nothing on
   * this path runs from a poll.
   */
  private Side side(Path owner, String rev, String pathInOwner) {
    Shell.Result size =
        Shell.run(owner, List.of("git", "cat-file", "-s", rev + ":" + pathInOwner), 15);
    if (size.exitCode() == -1) {
      // Shell reports -1 for a timeout, a kill or an IOException - cases where git never answered.
      // Reading that as "absent in that revision" would render the file as added in this commit,
      // beside a real hash: an attribution invented out of a process that failed to run.
      throw new IllegalStateException("git did not answer for " + rev + ":" + pathInOwner);
    }
    if (!size.ok()) {
      // Git answered, and its answer is that the path is not in that revision: added, deleted or
      // renamed. An empty side is what that means.
      return Side.ABSENT;
    }
    try {
      if (Long.parseLong(size.stdout().strip()) > props.getMaxDiffBytes()) {
        return new Side("", true);
      }
    } catch (NumberFormatException e) {
      return Side.ABSENT;
    }
    return new Side(
        Shell.run(owner, List.of("git", "show", rev + ":" + pathInOwner), 15).stdout(), false);
  }

  /** A NUL byte is what git itself uses to decide a file is binary. */
  private static boolean isBinary(String content) {
    return content.indexOf(0) >= 0;
  }
}
