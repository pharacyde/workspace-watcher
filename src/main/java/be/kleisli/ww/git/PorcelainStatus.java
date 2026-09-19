package be.kleisli.ww.git;

import java.util.ArrayList;
import java.util.List;

/**
 * The output of {@code git status --porcelain=v1 -z}, read into entries.
 *
 * <p>Pure: no filesystem, no process. What an entry means on disk - a submodule, a nested checkout,
 * a symlink - is {@link GitService}'s business, because that needs a stat.
 */
final class PorcelainStatus {

  private PorcelainStatus() {}

  /**
   * One entry: the two status columns and the path as git printed it.
   *
   * <p>A rename or copy keeps only the new path. A directory entry loses its trailing slash, so it
   * can serve as a prefix without doubling the separator.
   */
  record Entry(char index, char worktree, String path) {

    /** Known to the index at all; {@code ??} is the one shape that is not. */
    boolean tracked() {
      return index != '?';
    }

    /** Something about this entry is in the index and not only in the working tree. */
    boolean staged() {
      return index != ' ' && index != '?';
    }

    String status() {
      return statusOf(index, worktree);
    }
  }

  /**
   * Splits the NUL-separated output into entries.
   *
   * <p>-z output has no quoting: a space or an accent arrives as itself. A rename or copy is two
   * NUL fields, new path first, and the old one must not be read as an entry of its own. See
   * docs/collectors.md, "git status quotes paths unless told not to".
   */
  static List<Entry> parse(String stdout) {
    List<Entry> entries = new ArrayList<>();
    String[] fields = stdout.split("\0");
    for (int i = 0; i < fields.length; i++) {
      String field = fields[i];
      if (field.length() < 4) {
        continue;
      }
      char index = field.charAt(0);
      char worktree = field.charAt(1);
      if (isRenameOrCopy(index, worktree)) {
        i++;
      }
      entries.add(new Entry(index, worktree, withoutTrailingSlash(field.substring(3))));
    }
    return entries;
  }

  private static boolean isRenameOrCopy(char index, char worktree) {
    return index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C';
  }

  /** An untracked nested repository is reported as "?? tool/", and "tool//b.txt" is not a path. */
  private static String withoutTrailingSlash(String path) {
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  static String statusOf(char index, char worktree) {
    if (index == '?') return "untracked";
    if (index == 'A' || worktree == 'A') return "added";
    if (index == 'D' || worktree == 'D') return "deleted";
    if (index == 'R') return "renamed";
    return "modified";
  }
}
