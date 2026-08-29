package be.kleisli.ww.proc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTreeServiceTest {

  private static final Path WORKSPACE = Path.of("/repo/project");

  @Test
  @DisplayName("pairs each p-line with the n-line that follows it")
  void parsesPidAndPath() {
    Map<Long, String> matched =
        ProcessTreeService.parseCwdLines(
            List.of("p123", "n/repo/project", "p456", "n/repo/project/frontend"), WORKSPACE);

    assertThat(matched)
        .containsExactly(
            Map.entry(123L, "/repo/project"), Map.entry(456L, "/repo/project/frontend"));
  }

  @Test
  @DisplayName("keeps only processes inside the workspace")
  void filtersOutsideWorkspace() {
    Map<Long, String> matched =
        ProcessTreeService.parseCwdLines(
            List.of("p1", "n/elsewhere", "p2", "n/repo/project/src", "p3", "n/"), WORKSPACE);

    assertThat(matched).containsOnlyKeys(2L);
  }

  @Test
  @DisplayName("does not match a sibling directory that merely shares a prefix")
  void doesNotMatchPrefixSibling() {
    // /repo/project-old starts with /repo/project as a string but is a different directory.
    Map<Long, String> matched =
        ProcessTreeService.parseCwdLines(List.of("p1", "n/repo/project-old/src"), WORKSPACE);

    assertThat(matched).isEmpty();
  }

  @Test
  @DisplayName("survives truncated or malformed output rather than throwing")
  void toleratesMalformedOutput() {
    Map<Long, String> matched =
        ProcessTreeService.parseCwdLines(
            List.of("", "nnothing-before-it", "pnot-a-number", "n/repo/project", "p9"), WORKSPACE);

    assertThat(matched).isEmpty();
  }

  @Test
  @DisplayName("lists only regular files on numeric descriptors, with the log among them")
  void listsOpenFiles() {
    List<ProcessTreeService.OpenFile> files =
        ProcessTreeService.parseOpenFiles(
            List.of(
                "p1234",
                // The executable and a shared library: open, and not what anyone means by it.
                "ftxt",
                "ar",
                "tREG",
                "n/usr/bin/node",
                "fmem",
                "tREG",
                "n/usr/lib/libSystem.dylib",
                "fcwd",
                "tDIR",
                "n/repo/project",
                // stdout redirected into a build log inside the workspace: the row worth clicking.
                "f1",
                "aw",
                "tREG",
                "n/repo/project/build.log",
                // A socket has no path to follow.
                "f5",
                "au",
                "tIPv4",
                "n*:8080",
                // A regular file outside the workspace: listed, but not openable from here.
                "f7",
                "ar",
                "tREG",
                "n/var/log/system.log"),
            WORKSPACE);

    assertThat(files)
        .extracting(ProcessTreeService.OpenFile::path)
        .containsExactly("/repo/project/build.log", "/var/log/system.log");
    assertThat(files.getFirst().fd()).isEqualTo("1");
    assertThat(files.getFirst().mode()).isEqualTo("w");
    assertThat(files.getFirst().relativePath()).isEqualTo("build.log");
    // Outside the workspace: named, and deliberately not reachable through the tail.
    assertThat(files.getLast().relativePath()).isNull();
  }

  @Test
  @DisplayName("a sibling directory sharing a prefix is not inside the workspace")
  void openFileInPrefixSibling() {
    List<ProcessTreeService.OpenFile> files =
        ProcessTreeService.parseOpenFiles(
            List.of("f3", "ar", "tREG", "n/repo/project-old/build.log"), WORKSPACE);

    assertThat(files.getFirst().relativePath()).isNull();
  }

  @Test
  @DisplayName("a workspace reached through a symlink matches what lsof answers with")
  void matchesThroughASymlink(@TempDir Path tmp) throws IOException {
    // macOS /tmp is a link to /private/tmp, so lsof spells the same directory differently than the
    // watcher was told. Every process was filtered out and the panel sat empty with no error.
    // lsof always prints the fully resolved path, which on macOS also resolves the temp directory
    // itself - /var/folders is a link to /private/var/folders.
    Path real = Files.createDirectory(tmp.resolve("real")).toRealPath();
    Path link = Files.createSymbolicLink(tmp.resolve("link"), real);

    Map<Long, String> matched =
        ProcessTreeService.parseCwdLines(List.of("p1", "n" + real + "/src"), link);

    assertThat(matched).containsOnlyKeys(1L);
    assertThat(
            ProcessTreeService.parseOpenFiles(
                List.of("f1", "aw", "tREG", "n" + real + "/build.log"), link))
        .singleElement()
        .extracting(ProcessTreeService.OpenFile::relativePath)
        .isEqualTo("build.log");
  }

  @Test
  @DisplayName("only a process the panel is showing may be asked what it has open")
  void onlyWatchedPidsMayBeAsked() {
    // Without this, anything that reaches the loopback port could walk pids 1..N and read the
    // paths of every file every process on the machine holds open.
    List<ProcessTreeService.Node> tree =
        List.of(
            new ProcessTreeService.Node(
                10,
                "build",
                "/repo/project",
                List.of(new ProcessTreeService.Node(11, "javac", "/repo/project", List.of()))));

    assertThat(ProcessTreeService.isWatched(11, tree)).isTrue();
    assertThat(ProcessTreeService.isWatched(10, tree)).isTrue();
    assertThat(ProcessTreeService.isWatched(12, tree)).isFalse();
    assertThat(ProcessTreeService.isWatched(11, List.of())).isFalse();
  }

  @Test
  @DisplayName("an access mode lsof could not determine is empty, not a space")
  void blankModeIsEmpty() {
    // lsof writes a space when it cannot tell, and the fd column is right-aligned: rendering that
    // space shifted the number one character out of line with its neighbours.
    List<ProcessTreeService.OpenFile> files =
        ProcessTreeService.parseOpenFiles(
            List.of("f1", "a ", "tREG", "n/repo/project/out.log"), WORKSPACE);

    assertThat(files.getFirst().mode()).isEmpty();
  }
}
