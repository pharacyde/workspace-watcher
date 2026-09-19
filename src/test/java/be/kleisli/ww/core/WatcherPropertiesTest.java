package be.kleisli.ww.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WatcherPropertiesTest {

  private static final Path CWD = Path.of(System.getProperty("user.dir")).toAbsolutePath();

  @Test
  @DisplayName("with no database, the sidecar files sit in the working directory itself")
  void blankDatabaseMeansWorkingDirectory() {
    // Path.of("").toAbsolutePath().getParent() is the working directory's parent, not the working
    // directory. Three call sites each did that and then checked for null, which never happened,
    // so "set database to empty to run without history" quietly put active-workspace, guard.json
    // and pricing.json one level above where the watcher was started.
    WatcherProperties props = new WatcherProperties();
    props.setDatabase("");
    assertThat(props.sidecarDirectory()).isEqualTo(CWD);

    props.setDatabase("   ");
    assertThat(props.sidecarDirectory()).isEqualTo(CWD);

    props.setDatabase(null);
    assertThat(props.sidecarDirectory()).isEqualTo(CWD);
  }

  @Test
  @DisplayName("a relative database keeps its sidecars beside it, under the working directory")
  void relativeDatabaseResolvesAgainstWorkingDirectory() {
    WatcherProperties props = new WatcherProperties();
    props.setDatabase("state/events.db");
    assertThat(props.sidecarDirectory()).isEqualTo(CWD.resolve("state"));

    props.setDatabase("events.db");
    assertThat(props.sidecarDirectory()).isEqualTo(CWD);
  }

  @Test
  @DisplayName("an absolute database keeps its sidecars beside it")
  void absoluteDatabaseUsesItsParent() {
    WatcherProperties props = new WatcherProperties();
    props.setDatabase("/var/lib/watcher/../watcher/events.db");
    assertThat(props.sidecarDirectory()).isEqualTo(Path.of("/var/lib/watcher"));
  }
}
