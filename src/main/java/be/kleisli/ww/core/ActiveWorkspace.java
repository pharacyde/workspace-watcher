package be.kleisli.ww.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The workspace currently being observed, which can change while the app runs.
 *
 * <p>A watcher no longer has to be told what to look at. Started with nothing configured it simply
 * waits: the first agent hook that arrives says which project someone is actually working in, and
 * that becomes the workspace. Switching to another discovered project is then just a matter of
 * setting this.
 *
 * <p>Collectors read this on every poll rather than caching a path, so a switch takes effect within
 * one polling interval with nothing to restart.
 */
@Component
public class ActiveWorkspace {

  private static final Logger log = LoggerFactory.getLogger(ActiveWorkspace.class);

  private final StateStream<String> stream = new StateStream<>();
  private volatile Path current;

  public ActiveWorkspace(WatcherProperties props) {
    String configured = props.getWorkspace();
    if (configured != null && !configured.isBlank()) {
      set(Paths.get(configured));
    }
  }

  /** The workspace being observed, or null when none has been chosen yet. */
  public Path get() {
    return current;
  }

  public boolean isSet() {
    return current != null;
  }

  public StateStream<String> stream() {
    return stream;
  }

  /**
   * Points the watcher at a workspace.
   *
   * @return true when this changed the workspace, so callers can reset whatever they cached
   */
  public synchronized boolean set(Path path) {
    Path resolved = path.toAbsolutePath().normalize();
    if (!Files.isDirectory(resolved)) {
      throw new IllegalArgumentException("not a directory: " + resolved);
    }
    if (resolved.equals(current)) {
      return false;
    }
    log.info("watching {}", resolved);
    current = resolved;
    stream.publish(resolved.toString());
    return true;
  }
}
