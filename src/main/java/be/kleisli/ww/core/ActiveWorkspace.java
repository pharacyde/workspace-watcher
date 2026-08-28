package be.kleisli.ww.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  private final WatcherProperties props;

  public ActiveWorkspace(WatcherProperties props) {
    this.props = props;
    String configured = props.getWorkspace();
    if (configured != null && !configured.isBlank()) {
      // Explicitly pinned on the command line: that wins over anything remembered.
      set(Paths.get(configured));
      return;
    }
    restore();
  }

  /**
   * Reinstates the workspace chosen last time.
   *
   * <p>Remembered on the server rather than in the browser, because the server is what does the
   * watching. If a browser remembered the choice while the watcher adopted whatever was most
   * recently active, the two would disagree and the panels would describe a different project than
   * the header claimed.
   */
  private void restore() {
    Path file = rememberedFile();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      String remembered = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (!remembered.isEmpty() && Files.isDirectory(Path.of(remembered))) {
        set(Path.of(remembered));
      }
    } catch (IOException | RuntimeException e) {
      // Falling back to discovery is a fine outcome; a corrupt note is not worth a failed start.
      log.debug("cannot read remembered workspace: {}", e.toString());
    }
  }

  private void remember(Path path) {
    Path file = rememberedFile();
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, path.toString(), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      log.debug("cannot remember workspace: {}", e.toString());
    }
  }

  private Path rememberedFile() {
    Path database = Path.of(props.getDatabase()).toAbsolutePath().normalize();
    Path directory =
        database.getParent() != null
            ? database.getParent()
            : Path.of(System.getProperty("user.dir"));
    return directory.resolve("active-workspace");
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
    remember(resolved);
    stream.publish(resolved.toString());
    return true;
  }
}
