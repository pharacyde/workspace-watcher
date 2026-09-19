package be.kleisli.ww.claude;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
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

  @Test
  @DisplayName("a non-ASCII path is escaped over its UTF-8 bytes, so the hook can agree with it")
  void escapesNonAsciiPathBytewise() {
    // Measured on this machine with /Users/josé/proj ect: the hook's bash expansion gave
    // -Users-josé-proj-ect under the UTF-8 locale and -Users-jos---proj-ect under LC_ALL=C, while
    // Java gave -Users-jos--proj-ect. Three names for one project, and the drain looked up the
    // third: every hook event for that project was silently never picked up. Bytes are the one
    // thing both sides can count the same way, which is why é escapes to two dashes.
    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));

    assertThat(registry.spoolFor(Path.of("/Users/josé/proj ect")).getFileName())
        .hasToString("-Users-jos---proj-ect");
    // The decomposed form macOS often stores is a different byte sequence and gets a different
    // name. That is deliberate: the hook sees the same bytes and cannot normalise them without a
    // fork, so folding the two forms together here would reopen the disagreement.
    assertThat(registry.spoolFor(Path.of("/Users/josé/proj ect")).getFileName())
        .hasToString("-Users-jose---proj-ect");
  }

  @Test
  @DisplayName("the hook script creates exactly the directory the drain looks up, in any locale")
  void hookScriptAgreesWithSpoolFor() throws IOException, InterruptedException {
    // Surefire runs from the project root, so the script is where the README points to.
    Path script = Path.of("hooks/workspace-watcher-hook.sh").toAbsolutePath();
    Assumptions.assumeTrue(Files.isRegularFile(script), "hook script not found: " + script);
    WorkspaceRegistry registry = new WorkspaceRegistry(props, new ActiveWorkspace(props));

    for (String project :
        List.of(
            "/Users/josé/proj ect", // é as one code point (NFC)
            "/Users/josé/proj ect", // e plus combining acute (NFD, as macOS often stores it)
            "/Users/me/plain")) {
      // Bracket ranges collate under a UTF-8 locale and count bytes under C; the script must give
      // one answer regardless of which locale Claude Code happens to launch it with.
      for (String locale : List.of("en_US.UTF-8", "C")) {
        Path base = Files.createDirectories(tmp.resolve("spool-" + locale));
        runHook(script, base, project, locale);

        List<Path> created;
        try (Stream<Path> dirs = Files.list(base)) {
          created = dirs.sorted().toList();
        }
        assertThat(created)
            .as("directory for %s under LC_ALL=%s", project, locale)
            .map(dir -> dir.getFileName().toString())
            .containsExactly(registry.spoolFor(Path.of(project)).getFileName().toString());
        assertThat(Files.readString(created.getFirst().resolve(".workspace"), UTF_8))
            .isEqualTo(project + "\n");
        deleteTree(base);
      }
    }
  }

  private static void runHook(Path script, Path spoolBase, String project, String locale)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
    Map<String, String> env = pb.environment();
    env.put("WORKSPACE_WATCHER_SPOOL", spoolBase.toString());
    env.put("CLAUDE_PROJECT_DIR", project);
    env.put("LC_ALL", locale);
    env.put("LANG", locale);
    env.remove("WORKSPACE_WATCHER_URL");
    pb.redirectErrorStream(true);
    Process process = pb.start();
    process.getOutputStream().write("{\"hook_event_name\":\"PostToolUse\"}".getBytes(UTF_8));
    process.getOutputStream().close();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("hook finished").isTrue();
    assertThat(process.exitValue()).as("hook exit code, output: %s", output).isZero();
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
