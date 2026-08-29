package be.kleisli.ww.fs;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.git.GitService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class FileChangeServiceTest {

  @TempDir Path tmp;

  private Path repo;
  private GitService git;
  private FileChangeService changes;

  @BeforeEach
  void setUp() throws IOException {
    repo = tmp.resolve("repo");
    Files.createDirectories(repo);
    Files.writeString(repo.resolve("App.java"), "class App {}\n");

    git("init", "--initial-branch=main");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "Test");
    git("add", ".");
    git("commit", "-m", "initial");

    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(repo.toString());
    git = new GitService(new ActiveWorkspace(props), props);
    git.refresh();
    changes = new FileChangeService(git);
  }

  private void git(String... args) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Shell.run(repo, command, 20);
  }

  @Test
  @DisplayName("a changed file is reported")
  void reportsAChangedFile() {
    StepVerifier.create(changes.watch("App.java").take(2))
        .assertNext(change -> assertThat(change.gone()).isFalse())
        .then(
            () -> {
              try {
                Files.writeString(repo.resolve("App.java"), "class App { int x; }\n");
              } catch (IOException e) {
                throw new AssertionError(e);
              }
            })
        .assertNext(change -> assertThat(change.size()).isEqualTo(21))
        .verifyComplete();
  }

  @Test
  @DisplayName("a commit is reported although the file did not change")
  void reportsACommitThatLeftTheFileAlone() {
    // The left-hand side of an open diff is `git show HEAD:<path>`, so a commit changes the diff
    // completely while leaving the file's size and modification time exactly as they were. Without
    // this the panel went on showing the differences against the previous commit, with the live
    // badge lit over a diff that could no longer arrive.
    StepVerifier.create(changes.watch("App.java").take(2))
        .assertNext(change -> assertThat(change.gone()).isFalse())
        .then(
            () -> {
              try {
                Files.writeString(repo.resolve("Other.java"), "class Other {}\n");
              } catch (IOException e) {
                throw new AssertionError(e);
              }
              git("add", ".");
              git("commit", "-m", "second");
              // In the running app the scanner does this; here nothing else would.
              git.refresh();
            })
        .assertNext(change -> assertThat(change.path()).isEqualTo("App.java"))
        .verifyComplete();
  }

  @Test
  @DisplayName("a quiet file and a quiet repository say nothing at all")
  void saysNothingWhileNothingHappens() {
    StepVerifier.create(changes.watch("App.java"))
        .assertNext(change -> assertThat(change.gone()).isFalse())
        .expectNoEvent(Duration.ofSeconds(2))
        .thenCancel()
        .verify();
  }
}
