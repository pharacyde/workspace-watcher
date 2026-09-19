package be.kleisli.ww.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import be.kleisli.ww.git.PorcelainStatus.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parser on its own, with output shaped exactly as {@code git status --porcelain=v1 -z} prints
 * it: NUL after every field, no quoting, and no trailing newline.
 */
class PorcelainStatusTest {

  @Test
  @DisplayName("a rename is two NUL fields, new path first, and the old one is not an entry")
  void renameKeepsOnlyTheNewPath() {
    String out = "R  src/New.java\0src/Old.java\0 M other.txt\0";

    assertThat(PorcelainStatus.parse(out))
        .extracting(Entry::path, Entry::status, Entry::staged)
        .containsExactly(
            tuple("src/New.java", "renamed", true), tuple("other.txt", "modified", false));
  }

  @Test
  @DisplayName("a copy has the same two-field shape as a rename")
  void copySkipsTheSourceField() {
    String out = "C  copy.txt\0orig.txt\0?? loose.txt\0";

    assertThat(PorcelainStatus.parse(out))
        .extracting(Entry::path, Entry::tracked)
        .containsExactly(tuple("copy.txt", true), tuple("loose.txt", false));
  }

  @Test
  @DisplayName("an untracked nested repository loses its trailing slash")
  void stripsTheTrailingSlashOfADirectoryEntry() {
    // "?? tool/" used as a prefix would otherwise produce "tool//b.txt".
    assertThat(PorcelainStatus.parse("?? tool/\0"))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.path()).isEqualTo("tool");
              assertThat(entry.tracked()).isFalse();
              assertThat(entry.staged()).isFalse();
            });
  }

  @Test
  @DisplayName("a space or an accent arrives raw, with no quotes and no octal")
  void keepsNonAsciiPathsAsPrinted() {
    String out = " M café sp.txt\0?? nieuw é.txt\0";

    assertThat(PorcelainStatus.parse(out))
        .extracting(Entry::path, Entry::status)
        .containsExactly(tuple("café sp.txt", "modified"), tuple("nieuw é.txt", "untracked"));
  }

  @Test
  @DisplayName("the two columns classify the entry, index first")
  void classifiesByColumns() {
    assertThat(PorcelainStatus.statusOf('?', '?')).isEqualTo("untracked");
    assertThat(PorcelainStatus.statusOf('A', ' ')).isEqualTo("added");
    assertThat(PorcelainStatus.statusOf(' ', 'D')).isEqualTo("deleted");
    assertThat(PorcelainStatus.statusOf('D', ' ')).isEqualTo("deleted");
    assertThat(PorcelainStatus.statusOf('R', 'M')).isEqualTo("renamed");
    assertThat(PorcelainStatus.statusOf(' ', 'M')).isEqualTo("modified");
    assertThat(PorcelainStatus.statusOf('M', 'M')).isEqualTo("modified");
  }

  @Test
  @DisplayName("empty output and the empty last field are not entries")
  void ignoresEmptyOutput() {
    assertThat(PorcelainStatus.parse("")).isEmpty();
    assertThat(PorcelainStatus.parse("\0")).isEmpty();
  }
}
