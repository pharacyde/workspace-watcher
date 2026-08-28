package be.kleisli.ww.proc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
