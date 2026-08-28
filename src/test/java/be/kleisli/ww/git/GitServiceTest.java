package be.kleisli.ww.git;

import static org.assertj.core.api.Assertions.assertThat;
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
  @DisplayName("detects a binary file instead of returning mojibake")
  void detectsBinary() throws IOException {
    Files.write(module.resolve("blob.bin"), new byte[] {1, 2, 0, 3});
    GitService service = serviceWatching(repo);

    GitService.Versions versions = service.versions("module/blob.bin");
    assertThat(versions.binary()).isTrue();
    assertThat(versions.working()).isEmpty();
  }
}
