package be.kleisli.ww.fs;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.git.GitService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScanServiceTest {

  @TempDir Path tmp;

  private Path workspace;
  private WatcherProperties props;
  private EventBus bus;
  private WorkspaceScanService scanner;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
    // No pacing in tests: the scanner normally spaces itself out by how long a walk takes.
    props.setFsPollMs(0);

    ActiveWorkspace active = new ActiveWorkspace(props);
    bus = new EventBus(props);
    scanner = new WorkspaceScanService(props, active, bus, new GitService(active, props));
  }

  private List<WatchEvent> since(int index) {
    List<WatchEvent> all = bus.replay();
    return all.subList(Math.min(index, all.size()), all.size());
  }

  @Test
  @DisplayName("the first pass establishes a baseline instead of replaying the tree")
  void firstPassDoesNotFlood() throws IOException {
    for (int i = 0; i < 20; i++) {
      Files.writeString(workspace.resolve("file" + i + ".txt"), "x");
    }
    scanner.scan();

    // Twenty "created" events for files that were already there would bury whatever happens next.
    assertThat(bus.replay())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.source()).isEqualTo(WatchEvent.Source.SYSTEM);
              assertThat(event.type()).isEqualTo("BASELINE");
            });
  }

  @Test
  @DisplayName("reports a file created after the baseline")
  void reportsNewFile() throws IOException {
    scanner.scan();
    int baseline = bus.replay().size();

    Files.writeString(workspace.resolve("new.txt"), "x");
    scanner.scan();

    assertThat(since(baseline))
        .anySatisfy(
            event -> {
              assertThat(event.source()).isEqualTo(WatchEvent.Source.FS);
              assertThat(event.type()).isEqualTo("CREATED");
              assertThat(event.path()).isEqualTo("new.txt");
            });
  }

  @Test
  @DisplayName("collapses a large change into one summary")
  void collapsesBulkChanges() throws IOException {
    props.setMaxFileEventsPerScan(5);
    scanner.scan();
    int baseline = bus.replay().size();

    for (int i = 0; i < 40; i++) {
      Files.writeString(workspace.resolve("bulk" + i + ".txt"), "x");
    }
    scanner.scan();

    // A checkout changes thousands of files. Listing them buries the agent's own actions and
    // evicts real history from the replay buffer.
    assertThat(since(baseline))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.type()).isEqualTo("BULK");
              assertThat(event.summary()).contains("40 files changed at once");
            });
  }

  @Test
  @DisplayName("ignores build and version-control directories")
  void skipsIgnoredDirectories() throws IOException {
    scanner.scan();
    int baseline = bus.replay().size();

    Files.createDirectories(workspace.resolve("target/classes"));
    Files.writeString(workspace.resolve("target/classes/App.class"), "x");
    Files.createDirectories(workspace.resolve(".git"));
    Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/main");
    scanner.scan();

    assertThat(since(baseline)).isEmpty();
  }

  @Test
  @DisplayName("ignores the .git file a linked worktree uses")
  void skipsGitAsAFile() throws IOException {
    scanner.scan();
    int baseline = bus.replay().size();

    // In a worktree .git is a file, so a directory-only filter never sees it and it would be
    // reported on every git operation.
    Files.writeString(workspace.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/wt");
    scanner.scan();

    assertThat(since(baseline)).isEmpty();
  }

  @Test
  @DisplayName("a deleted file is reported once")
  void reportsDeletion() throws IOException {
    Files.writeString(workspace.resolve("doomed.txt"), "x");
    scanner.scan();
    int baseline = bus.replay().size();

    Files.delete(workspace.resolve("doomed.txt"));
    scanner.scan();

    assertThat(since(baseline))
        .singleElement()
        .satisfies(event -> assertThat(event.type()).isEqualTo("DELETED"));
  }

  @Test
  @DisplayName("does nothing while no workspace is being watched")
  void quietWithoutWorkspace() {
    // A separate state directory, because the first version of this test failed for a good reason:
    // ActiveWorkspace reinstates the workspace it remembers, so clearing the property alone still
    // left one being watched.
    WatcherProperties fresh = new WatcherProperties();
    fresh.setDatabase(tmp.resolve("nothing-remembered/events.db").toString());
    ActiveWorkspace active = new ActiveWorkspace(fresh);
    assertThat(active.isSet()).isFalse();

    EventBus otherBus = new EventBus(fresh);
    new WorkspaceScanService(fresh, active, otherBus, new GitService(active, fresh)).scan();

    assertThat(otherBus.replay()).isEmpty();
  }
}
