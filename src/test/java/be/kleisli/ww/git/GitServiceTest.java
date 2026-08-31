package be.kleisli.ww.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitServiceTest {

  @TempDir Path tmp;

  private Path repo;
  private Path module;

  @BeforeEach
  void setUp() throws IOException {
    repo = tmp.resolve("repo");
    module = repo.resolve("module");
    Files.createDirectories(module.resolve("src"));
    Files.writeString(module.resolve("src/App.java"), "class App {}\n");

    git("init", "--initial-branch=main");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "Test");
    git("add", ".");
    git("commit", "-m", "initial");
  }

  private void git(String... args) {
    List<String> command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Shell.run(repo, command, 20);
  }

  private void run(Path directory, String... args) {
    Shell.run(directory, List.of(args), 20);
  }

  private GitService serviceWatching(Path workspace) {
    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    GitService service = new GitService(new ActiveWorkspace(props), props);
    service.refresh();
    return service;
  }

  @Test
  @DisplayName("a commit refreshes the working tree even though no file changed")
  void refreshesAfterACommitWithoutFileChanges() throws IOException {
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    GitService service = serviceWatching(repo);
    assertThat(service.current().files()).hasSize(1);

    // The scanner only refreshes when it saw a file change, and committing changes no file: every
    // mtime and size in the tree is what it was a moment earlier. Without a second signal the panel
    // kept listing what had just been committed, and clicking one of those rows opened a diff with
    // nothing in it.
    git("add", ".");
    git("commit", "-m", "second");

    service.refreshIfGitChanged();
    assertThat(service.current().files()).isEmpty();
    assertThat(service.current().headSubject()).isEqualTo("second");
  }

  @Test
  @DisplayName("nothing happening in git costs nothing and changes nothing")
  void quietRepositoryIsNotRefreshed() {
    GitService service = serviceWatching(repo);
    GitService.Snapshot before = service.current();

    service.refreshIfGitChanged();

    assertThat(service.current()).isSameAs(before);
  }

  @Test
  @DisplayName("reads branch and changed files at the repository root")
  void readsStatusAtRoot() throws IOException {
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    GitService service = serviceWatching(repo);

    GitService.Snapshot snapshot = service.current();
    assertThat(snapshot.repo()).isTrue();
    assertThat(snapshot.branch()).isEqualTo("main");
    assertThat(snapshot.files())
        .extracting(GitService.FileStatus::path)
        .containsExactly("module/src/App.java");
  }

  @Test
  @DisplayName("returns both sides of a file when the workspace is a subdirectory")
  void resolvesVersionsFromASubdirectoryWorkspace() throws IOException {
    // The regression this exists for: git reports repository-root-relative paths whatever
    // directory it runs in, so resolving them against a workspace that is a subdirectory looked
    // for module/module/src/App.java, found nothing, and rendered the file as entirely deleted.
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    GitService service = serviceWatching(module);

    String path = service.current().files().getFirst().path();
    assertThat(path).isEqualTo("module/src/App.java");

    GitService.Versions versions = service.versions(path);
    assertThat(versions.head()).isEqualTo("class App {}\n");
    assertThat(versions.working()).isEqualTo("class App { int x; }\n");
  }

  @Test
  @DisplayName("lists only files under the workspace when it is a subdirectory")
  void scopesStatusToTheWorkspace() throws IOException {
    Files.writeString(repo.resolve("outside.txt"), "not in the module\n");
    Files.writeString(module.resolve("inside.txt"), "in the module\n");

    assertThat(serviceWatching(module).current().files())
        .extracting(GitService.FileStatus::path)
        .containsExactly("module/inside.txt");
  }

  @Test
  @DisplayName("an untracked file has an empty head side")
  void reportsUntrackedFile() throws IOException {
    Files.writeString(module.resolve("New.java"), "class New {}\n");
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/New.java");
    assertThat(versions.head()).isEmpty();
    assertThat(versions.working()).isEqualTo("class New {}\n");
  }

  @Test
  @DisplayName("resolves a file inside a submodule against the submodule's own repository")
  void resolvesInsideSubmodule() throws IOException {
    // A submodule is a repository of its own. Asking the superproject for a file inside it fails
    // with "exists on disk, but not in HEAD", and the file would render as entirely new.
    Path inner = tmp.resolve("inner");
    Files.createDirectories(inner);
    Files.writeString(inner.resolve("Inner.java"), "class Inner {}\n");
    run(inner, "git", "init", "--initial-branch=main");
    run(inner, "git", "config", "user.email", "test@example.com");
    run(inner, "git", "config", "user.name", "Test");
    run(inner, "git", "add", ".");
    run(inner, "git", "commit", "-m", "inner");

    run(
        repo,
        "git",
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        inner.toString(),
        "libs/inner");
    run(repo, "git", "commit", "-m", "add submodule");
    Files.writeString(repo.resolve("libs/inner/Inner.java"), "class Inner { int x; }\n");

    GitService service = serviceWatching(repo);
    GitService.Versions versions = service.versions("libs/inner/Inner.java");

    assertThat(versions.head()).isEqualTo("class Inner {}\n");
    assertThat(versions.working()).isEqualTo("class Inner { int x; }\n");
  }

  @Test
  @DisplayName("reports the branch of a linked worktree, not of the main one")
  void readsWorktreeBranch() throws IOException {
    Path worktree = tmp.resolve("wt");
    run(repo, "git", "worktree", "add", worktree.toString(), "-b", "feature");

    assertThat(serviceWatching(worktree).current().branch()).isEqualTo("feature");
  }

  @Test
  @DisplayName("stamps submodules of a linked worktree, whose gitdirs live under the main .git")
  void stampsSubmodulesFromALinkedWorktree() throws IOException {
    // A linked worktree has its own index and HEAD and shares the rest, and submodule gitdirs are
    // part of the rest: they stay under the main .git/modules. Looking under the worktree's own
    // gitdir finds nothing, which would bring the stale panel straight back for anyone working in
    // a worktree - the one layout the surrounding code goes out of its way to support.
    addSubmodule();
    Path worktree = tmp.resolve("wt");
    run(repo, "git", "worktree", "add", worktree.toString(), "-b", "feature");

    GitService service = serviceWatching(worktree);
    // A second round, because refresh() takes the stamp before it reads - so the first one runs
    // before the repository root is known and stamps nothing. Deliberately that way round: a
    // stamp taken after the read would miss anything that moved while the read was running.
    service.refresh();

    assertThat(service.stamp()).contains("inner:");
  }

  @Test
  @DisplayName("rejects a path that escapes the repository")
  void rejectsPathTraversal() {
    GitService service = serviceWatching(repo);
    assertThatThrownBy(() -> service.resolveInRepo("../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("reports a directory that is not a repository as such")
  void handlesNonRepository() throws IOException {
    Path plain = Files.createDirectory(tmp.resolve("plain"));
    assertThat(serviceWatching(plain).current().repo()).isFalse();
  }

  @Test
  @DisplayName("an unchanged file shows the commit that last touched it, not two blank sides")
  void fallsBackToTheLastCommitWhenNothingIsUncommitted() throws IOException {
    // The defect this exists for: committing made both sides identical, so an hour of work
    // vanished from the panel at exactly the moment it was finished.
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    git("commit", "-am", "add a field");
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/src/App.java");

    assertThat(versions.head()).isEqualTo("class App {}\n");
    assertThat(versions.working()).isEqualTo("class App { int x; }\n");
    assertThat(versions.live()).isFalse();
    assertThat(versions.leftRev()).isNotBlank();
    assertThat(versions.rightRev()).isNotBlank().isNotEqualTo(versions.leftRev());
  }

  @Test
  @DisplayName("uncommitted work still wins over the last commit, and says it is live")
  void prefersUncommittedWork() throws IOException {
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/src/App.java");

    assertThat(versions.live()).isTrue();
    assertThat(versions.leftRev()).isEqualTo("HEAD");
    assertThat(versions.rightRev()).isEqualTo("working copy");
  }

  @Test
  @DisplayName("a named revision is that commit against its parent")
  void readsANamedRevision() throws IOException {
    Files.writeString(module.resolve("src/App.java"), "class App { int x; }\n");
    git("commit", "-am", "add a field");
    Files.writeString(module.resolve("src/App.java"), "class App { int x, y; }\n");
    git("commit", "-am", "add another");
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/src/App.java", "HEAD~1");

    assertThat(versions.head()).isEqualTo("class App {}\n");
    assertThat(versions.working()).isEqualTo("class App { int x; }\n");
    assertThat(versions.live()).isFalse();
  }

  @Test
  @DisplayName("the root commit has an empty left side rather than failing")
  void handlesTheRootCommit() {
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/src/App.java", "HEAD");

    assertThat(versions.leftRev()).isEmpty();
    assertThat(versions.head()).isEmpty();
    assertThat(versions.working()).isEqualTo("class App {}\n");
  }

  /** A real submodule, because everything that goes wrong here goes wrong in git, not in Java. */
  private Path addSubmodule() throws IOException {
    Path inner = tmp.resolve("inner");
    Files.createDirectories(inner);
    Files.writeString(inner.resolve("Inner.java"), "class Inner {}\n");
    run(inner, "git", "init", "--initial-branch=main");
    run(inner, "git", "config", "user.email", "test@example.com");
    run(inner, "git", "config", "user.name", "Test");
    run(inner, "git", "add", ".");
    run(inner, "git", "commit", "-m", "inner");

    run(
        repo,
        "git",
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        inner.toString(),
        "libs/inner");
    run(repo, "git", "commit", "-m", "add submodule");
    return repo.resolve("libs/inner");
  }

  @Test
  @DisplayName(
      "a modified file inside a submodule appears in the working tree, not just the module")
  void listsFilesInsideADirtySubmodule() throws IOException {
    // Measured on a real superproject: `git status` answers " M libs/inner" and nothing else, for
    // eighteen repositories. On a project where all the work happens inside submodules that made
    // the whole git panel a row of dead ends.
    Path sub = addSubmodule();
    Files.writeString(sub.resolve("Inner.java"), "class Inner { int x; }\n");
    Files.writeString(sub.resolve("Fresh.java"), "class Fresh {}\n");

    GitService service = serviceWatching(repo);

    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .contains("libs/inner", "libs/inner/Inner.java", "libs/inner/Fresh.java");
    assertThat(service.current().files())
        .filteredOn(file -> file.path().equals("libs/inner/Fresh.java"))
        .extracting(GitService.FileStatus::status)
        .containsExactly("untracked");
  }

  @Test
  @DisplayName("a file listed from inside a submodule can still be diffed")
  void diffsAFileFoundInsideASubmodule() throws IOException {
    Path sub = addSubmodule();
    Files.writeString(sub.resolve("Inner.java"), "class Inner { int x; }\n");
    GitService service = serviceWatching(repo);

    String path =
        service.current().files().stream()
            .map(GitService.FileStatus::path)
            .filter(candidate -> candidate.equals("libs/inner/Inner.java"))
            .findFirst()
            .orElseThrow();

    assertThat(service.versions(path).head()).isEqualTo("class Inner {}\n");
  }

  @Test
  @DisplayName("a clean superproject does not descend into its submodules at all")
  void doesNotDescendIntoACleanSubmodule() throws IOException {
    // The cost is only defensible while it is paid per dirty submodule. A clean one must not be
    // asked anything, and the way to see that is that it is not in the list either.
    addSubmodule();
    GitService service = serviceWatching(repo);

    assertThat(service.current().files()).extracting(GitService.FileStatus::path).isEmpty();
  }

  @Test
  @DisplayName("a commit inside a submodule moves the stamp, so the panel refreshes")
  void noticesACommitInsideASubmodule() throws IOException {
    // Measured before this: a commit inside a submodule leaves the superproject's .git/index and
    // .git/HEAD byte-identical, so nothing refreshed and the panel stayed stale indefinitely -
    // the same defect as the commit case one repository up. Note it is the submodule's index that
    // moves; its HEAD does not, because it holds "ref: refs/heads/<branch>".
    Path sub = addSubmodule();
    Files.writeString(sub.resolve("Inner.java"), "class Inner { int x; }\n");
    GitService service = serviceWatching(repo);
    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .contains("libs/inner/Inner.java");
    run(sub, "git", "commit", "-am", "committed inside the submodule");
    service.refreshIfGitChanged();

    // Asserted on the stamp naming the submodule at all, rather than on the stamp having moved:
    // "it moved" is satisfied by the superproject rewriting its own index to update its stat cache
    // after a write, which has nothing to do with any submodule - an earlier version of this test
    // passed with the submodule stamping removed for exactly that reason. Nor on that index's
    // current size and time, because the descent runs `git status` inside the submodule and moves
    // them again; the stable claim is that the submodule is in the stamp, which is the fix.
    assertThat(service.stamp()).contains("inner:");
    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .doesNotContain("libs/inner/Inner.java");
  }

  @Test
  @DisplayName("a symlink to a directory outside the repository is not descended into")
  void doesNotFollowASymlinkOutOfTheRepository() throws IOException {
    // Files.isDirectory follows symlinks, so a link to somewhere else read as a submodule and this
    // listed and served what was inside it - on a server with no authentication, because
    // everything it serves is supposed to be inside the workspace.
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "not yours\n");
    Files.createSymbolicLink(repo.resolve("link"), outside);

    GitService service = serviceWatching(repo);

    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .contains("link")
        .doesNotContain("link/secret.txt");
    // And not called a submodule either. A tracked symlink to a directory passes the untracked
    // gate, so following the link here is what would let one be descended into; this is the
    // assertion that pins NOFOLLOW_LINKS rather than the gate. "symlink" rather than "untracked",
    // because a link has no content of its own to diff and clicking one opened two empty panes.
    assertThat(service.current().files())
        .filteredOn(file -> file.path().equals("link"))
        .extracting(GitService.FileStatus::status)
        .containsExactly("symlink");
  }

  @Test
  @DisplayName("a tracked file replaced by a directory is deleted, not a submodule")
  void doesNotMistakeADirectoryForASubmodule() throws IOException {
    // Git reports this as " D module/src/App.java": the index column is a space, which reads as
    // tracked, and the path is a directory on disk. Gating the descent on "tracked and a
    // directory" therefore ran git status in something that is not a repository root, where
    // porcelain paths still come back relative to the real root - so every file under it was
    // listed a second time under a doubled prefix, as a row that diffs to two empty panes.
    Files.delete(module.resolve("src/App.java"));
    Files.createDirectories(module.resolve("src/App.java"));
    Files.writeString(module.resolve("src/App.java/inner.txt"), "x\n");

    GitService service = serviceWatching(repo);

    assertThat(service.current().files())
        .filteredOn(file -> file.path().equals("module/src/App.java"))
        .extracting(GitService.FileStatus::status)
        .containsExactly("deleted");
    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .doesNotContain("module/src/App.java/module/src/App.java/inner.txt");
  }

  @Test
  @DisplayName("a file reached through a symlink out of the repository is refused")
  void refusesAFileBehindASymlinkOutOfTheRepository() throws IOException {
    // normalize() is lexical: it does not resolve symlinks, so "link/secret.txt" stayed inside the
    // repository on paper and outside it on disk. The listing side refuses to descend into a
    // symlink, but a caller reaches fileVersions without the listing.
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "not yours\n");
    Files.createSymbolicLink(repo.resolve("link"), outside);

    GitService service = serviceWatching(repo);

    assertThatThrownBy(() -> service.resolveInRepo("link/secret.txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside repository");
    // The link itself stays askable: it is inside the repository, and the listing shows it. Its
    // own real path is outside, which is exactly why the check looks at the parent rather than at
    // the path. Asserted as "does not throw" rather than on the returned path, because AssertJ's
    // Path.endsWith canonicalises what it is given and would follow this very link.
    assertThatCode(() -> service.resolveInRepo("link")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an untracked nested repository is listed but not descended into")
  void doesNotDescendIntoAnUntrackedNestedRepository() throws IOException {
    // Reported as "?? tool/", with a trailing slash git does not use anywhere else: as a prefix
    // that produced "tool//b.txt". And it is not a submodule of this project at all, so descending
    // into every vendored clone would be an unbounded number of `git status` calls per refresh.
    Path nested = Files.createDirectories(repo.resolve("tool"));
    Files.writeString(nested.resolve("b.txt"), "one\n");
    run(nested, "git", "init", "--initial-branch=main");
    run(nested, "git", "config", "user.email", "test@example.com");
    run(nested, "git", "config", "user.name", "Test");
    run(nested, "git", "add", ".");
    run(nested, "git", "commit", "-m", "nested");
    Files.writeString(nested.resolve("b.txt"), "two\n");

    GitService service = serviceWatching(repo);

    assertThat(service.current().files())
        .extracting(GitService.FileStatus::path)
        .contains("tool")
        .doesNotContain("tool/", "tool//b.txt", "tool/b.txt");
    // Not "submodule": it is a repository this project does not know about, and calling it one
    // put an empty fold on screen claiming its changes were listed underneath.
    assertThat(service.current().files())
        .filteredOn(file -> file.path().equals("tool"))
        .extracting(GitService.FileStatus::status)
        .containsExactly("nested");
  }

  @Test
  @DisplayName("rejects a revision that is really a path, which git would happily accept")
  void rejectsRevisionThatIsAPath() throws IOException {
    // Measured: `git log -1 --format='%h %p' docs` exits 0 and prints a real hash, because git
    // reads an argument that names a directory as a pathspec. Without a terminating "--" that
    // answer came back as a commit, with two empty sides and a hash that looked right.
    Files.createDirectories(repo.resolve("docs"));
    Files.writeString(repo.resolve("docs/note.md"), "a note\n");
    git("add", ".");
    git("commit", "-m", "docs");
    GitService service = serviceWatching(repo);

    assertThatThrownBy(() -> service.versions("module/src/App.java", "docs"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a committed blob over the limit is refused without being read")
  void refusesAnOversizedCommittedBlob() throws IOException {
    Files.writeString(module.resolve("big.txt"), "x".repeat(4096) + "\n");
    git("add", ".");
    git("commit", "-m", "big");

    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(repo.toString());
    props.setMaxDiffBytes(64);
    GitService service = new GitService(new ActiveWorkspace(props), props);
    service.refresh();

    GitService.Versions versions = service.versions("module/big.txt", "HEAD");

    assertThat(versions.tooLarge()).isTrue();
    assertThat(versions.working()).isEmpty();
    assertThat(versions.head()).isEmpty();
  }

  @Test
  @DisplayName("rejects a revision that would be read as an option by git")
  void rejectsRevisionThatIsAnOption() {
    GitService service = serviceWatching(repo);

    assertThatThrownBy(() -> service.versions("module/src/App.java", "--output=/tmp/pwned"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("detects a binary file instead of returning mojibake")
  void detectsBinary() throws IOException {
    Files.write(module.resolve("blob.bin"), new byte[] {1, 2, 0, 3});
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/blob.bin");
    assertThat(versions.binary()).isTrue();
    assertThat(versions.working()).isEmpty();
  }
}
