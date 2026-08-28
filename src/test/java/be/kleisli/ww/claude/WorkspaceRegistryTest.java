package be.kleisli.ww.claude;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRegistryTest {

  @TempDir Path tmp;

  private Path spoolBase;
  private WatcherProperties props;

  @BeforeEach
  void setUp() throws IOException {
    spoolBase = Files.createDirectory(tmp.resolve("spool"));
    props = new WatcherProperties();
    props.setSpool(spoolBase.toString());
    props.setDatabase(tmp.resolve("state/events.db").toString());
  }

  /** Writes what the hook writes: a directory named after the project, plus the marker. */
  private Path register(Path workspace) throws IOException {
    Path dir = Files.createDirectories(spoolBase.resolve(escape(workspace)));
    Files.writeString(dir.resolve(".workspace"), workspace + "\n", StandardCharsets.UTF_8);
    return dir;
  }

  private static String escape(Path path) {
    return path.toString().replaceAll("[^a-zA-Z0-9]", "-");
  }

  @Test
  @DisplayName("a project that has written a marker is registered")
  void findsRegisteredWorkspace() throws IOException {
    Path project = Files.createDirectory(tmp.resolve("project"));
    register(project);

    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    registry.scan();

    assertThat(registry.current())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.path()).isEqualTo(project.toString());
              assertThat(entry.exists()).isTrue();
            });
  }

  @Test
  @DisplayName("a spool directory without a marker is not a registration")
  void ignoresDirectoryWithoutMarker() throws IOException {
    Files.createDirectory(spoolBase.resolve("-some-leftover"));

    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    registry.scan();

    assertThat(registry.current()).isEmpty();
  }

  @Test
  @DisplayName("a project that has since been deleted is listed but marked gone")
  void marksMissingDirectory() throws IOException {
    Path project = Files.createDirectory(tmp.resolve("since-deleted"));
    register(project);
    Files.delete(project);

    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    registry.scan();

    assertThat(registry.current()).singleElement().satisfies(e -> assertThat(e.exists()).isFalse());
  }

  @Test
  @DisplayName("adopts the most recently active project when nothing is being watched")
  void adoptsMostRecent() throws IOException {
    Path older = Files.createDirectory(tmp.resolve("older"));
    Path newer = Files.createDirectory(tmp.resolve("newer"));
    Path olderSpool = register(older);
    Path newerSpool = register(newer);
    Files.setLastModifiedTime(olderSpool, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
    Files.setLastModifiedTime(newerSpool, java.nio.file.attribute.FileTime.fromMillis(2_000_000));

    ActiveWorkspace active = new ActiveWorkspace(props);
    new WorkspaceRegistry(props, active).scan();

    // Starting with no argument and following whoever is working is the point of the register.
    assertThat(active.get()).isEqualTo(newer.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("does not adopt over a workspace already being watched")
  void leavesAnExistingChoiceAlone() throws IOException {
    Path chosen = Files.createDirectory(tmp.resolve("chosen"));
    Path other = Files.createDirectory(tmp.resolve("other"));
    register(other);

    ActiveWorkspace active = new ActiveWorkspace(props);
    active.set(chosen);
    new WorkspaceRegistry(props, active).scan();

    assertThat(active.get()).isEqualTo(chosen.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("forgetting removes the registration and leaves the project alone")
  void forgetRemovesOnlyTheSpool() throws IOException {
    Path project = Files.createDirectory(tmp.resolve("project"));
    Files.writeString(project.resolve("a-file.txt"), "untouched");
    Path spool = register(project);
    Files.writeString(spool.resolve("20260101T000000-1-1.json"), "{}");

    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    registry.scan();

    assertThat(registry.forget(project.toString())).isTrue();
    assertThat(registry.current()).isEmpty();
    assertThat(Files.exists(spool)).isFalse();
    // Registration happens by itself, so unregistering must be possible - but it is the spool that
    // goes, never the project.
    assertThat(Files.readString(project.resolve("a-file.txt"))).isEqualTo("untouched");
  }

  @Test
  @DisplayName("forgetting something unregistered says so rather than pretending")
  void forgetUnknownIsFalse() {
    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    assertThat(registry.forget(tmp.resolve("never-registered").toString())).isFalse();
  }

  @Test
  @DisplayName("counts the events waiting on disk for a workspace")
  void countsPendingEvents() throws IOException {
    Path project = Files.createDirectory(tmp.resolve("project"));
    Path spool = register(project);
    Files.writeString(spool.resolve("20260101T000000-1-1.json"), "{}");
    Files.writeString(spool.resolve("20260101T000001-1-2.json"), "{}");

    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));
    registry.scan();

    assertThat(registry.current())
        .singleElement()
        .satisfies(e -> assertThat(e.pendingEvents()).isEqualTo(2));
  }
}
