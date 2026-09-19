package be.kleisli.ww.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a path a caller asked for is inside the workspace, on disk and not only on paper.
 * Shared because the same symlink hole was found twice, on the diff side and on the file tail.
 */
public final class PathGuard {

  private static final Logger log = LoggerFactory.getLogger(PathGuard.class);

  private PathGuard() {}

  /**
   * Whether {@code resolved} - already resolved against {@code root} and normalized - leaves it:
   * lexically ({@code ../../etc/passwd}) and then on disk (a symlink).
   */
  public static boolean escapes(Path root, Path resolved) {
    return !resolved.startsWith(root) || escapesBySymlink(root, resolved);
  }

  /**
   * Whether a path that is lexically inside the root leaves it on disk. Checked on the nearest
   * existing ancestor, because a deleted file is a normal thing to ask for here (collectors.md).
   */
  public static boolean escapesBySymlink(Path root, Path resolved) {
    try {
      Path existing = resolved;
      while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        existing = existing.getParent();
        if (existing == null) {
          return false;
        }
      }
      // Asking for the link itself is allowed; asking for something under a link that points out
      // is not, because the tail would serve the file the moment it appeared.
      Path real =
          existing.equals(resolved) && Files.isSymbolicLink(existing)
              ? existing.getParent().toRealPath()
              : existing.toRealPath();
      return !real.startsWith(root.toRealPath());
    } catch (IOException e) {
      // Cannot tell, so do not claim it is safe.
      log.debug("cannot real-path {}: {}", resolved, e.toString());
      return true;
    }
  }
}
