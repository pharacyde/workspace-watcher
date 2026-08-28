package be.kleisli.ww.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveWorkspaceTest {

  @TempDir Path tmp;

  private Path first;
  private Path second;
  private WatcherProperties props;

  @BeforeEach
  void setUp() throws IOException {
    first = Files.createDirectory(tmp.resolve("first"));
    second = Files.createDirectory(tmp.resolve("second"));
    props = new WatcherProperties();
    props.setDatabase(tmp.resolve("state/events.db").toString());
  }

  @Test
  @DisplayName("starts with nothing when none is configured")
  void startsUnset() {
    assertThat(new ActiveWorkspace(props).isSet()).isFalse();
  }

  @Test
  @DisplayName("a configured workspace is used as given")
  void usesConfigured() {
    props.setWorkspace(first.toString());
    assertThat(new ActiveWorkspace(props).get()).isEqualTo(first.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("remembers a choice and reinstates it next time")
  void remembersAcrossRestarts() {
    // The whole point of remembering on the server: a watcher restarted overnight comes back to
    // the project you chose, not to whichever one happens to have been touched most recently.
    ActiveWorkspace before = new ActiveWorkspace(props);
    before.set(second);

    assertThat(new ActiveWorkspace(props).get()).isEqualTo(second.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("an explicitly configured workspace outranks the remembered one")
  void configurationWinsOverMemory() {
    new ActiveWorkspace(props).set(second);

    props.setWorkspace(first.toString());
    assertThat(new ActiveWorkspace(props).get()).isEqualTo(first.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("forgets a remembered workspace that no longer exists")
  void ignoresRememberedDirectoryThatIsGone() throws IOException {
    ActiveWorkspace before = new ActiveWorkspace(props);
    before.set(second);
    Files.delete(second);

    assertThat(new ActiveWorkspace(props).isSet()).isFalse();
  }

  @Test
  @DisplayName("reports whether a change actually happened")
  void reportsRealChanges() {
    ActiveWorkspace active = new ActiveWorkspace(props);
    assertThat(active.set(first)).isTrue();
    assertThat(active.set(first)).isFalse();
    assertThat(active.set(second)).isTrue();
  }

  @Test
  @DisplayName("refuses something that is not a directory")
  void refusesNonDirectory() throws IOException {
    Path file = Files.writeString(tmp.resolve("a-file"), "x");
    ActiveWorkspace active = new ActiveWorkspace(props);

    assertThatThrownBy(() -> active.set(file)).isInstanceOf(IllegalArgumentException.class);
  }
}
