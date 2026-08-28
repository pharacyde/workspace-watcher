package be.kleisli.ww.fs;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.git.GitService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Layer 2: the generic safety net.
 *
 * <p>Deliberately a snapshot poller rather than {@code java.nio.file.WatchService}. On macOS the
 * JDK's WatchService is a polling implementation anyway (it does <em>not</em> bridge to FSEvents, a
 * claim that is often repeated and simply untrue), and its default sensitivity is measured in
 * seconds. A mtime+size snapshot diff is predictable, behaves identically on macOS and Linux, and
 * costs little once the usual build and dependency directories are pruned.
 *
 * <p>These events carry no PID. macOS FSEvents does not report one, and {@code lsof} only shows
 * descriptors that are still open — by the time a change is noticed the writer has long closed the
 * file. Attribution comes from layer 1 instead; guessing it here would be worse than useless.
 */
@Service
public class WorkspaceScanService {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceScanService.class);

  private record Stamp(long size, long modified) {}

  private final WatcherProperties props;
  private final EventBus bus;
  private final GitService git;
  private final Set<String> ignore;

  private Map<Path, Stamp> previous;

  public WorkspaceScanService(WatcherProperties props, EventBus bus, GitService git) {
    this.props = props;
    this.bus = bus;
    this.git = git;
    this.ignore = new HashSet<>(props.getIgnoreDirs());
  }

  @Scheduled(fixedDelayString = "${watcher.fs-poll-ms:750}")
  public void scan() {
    Path root = props.workspacePath();
    if (!Files.isDirectory(root)) {
      return;
    }
    Map<Path, Stamp> current;
    try {
      current = snapshot(root);
    } catch (IOException e) {
      log.debug("workspace scan failed: {}", e.toString());
      return;
    }

    if (previous == null) {
      // First pass only establishes the baseline; replaying the whole tree as "created"
      // would bury the session's real activity.
      previous = current;
      bus.publish(
          WatchEvent.of(WatchEvent.Source.SYSTEM, "BASELINE")
              .summary("watching " + root + " (" + current.size() + " files)")
              .path(root.toString()));
      git.refresh();
      return;
    }

    boolean changed = false;
    for (Map.Entry<Path, Stamp> entry : current.entrySet()) {
      Stamp before = previous.get(entry.getKey());
      if (before == null) {
        emit("CREATED", root, entry.getKey());
        changed = true;
      } else if (!before.equals(entry.getValue())) {
        emit("MODIFIED", root, entry.getKey());
        changed = true;
      }
    }
    for (Path gone : previous.keySet()) {
      if (!current.containsKey(gone)) {
        emit("DELETED", root, gone);
        changed = true;
      }
    }

    previous = current;
    if (changed) {
      git.refresh();
    }
  }

  private void emit(String type, Path root, Path file) {
    String relative = root.relativize(file).toString();
    bus.publish(WatchEvent.of(WatchEvent.Source.FS, type).summary(relative).path(relative));
  }

  private Map<Path, Stamp> snapshot(Path root) throws IOException {
    Map<Path, Stamp> map = new HashMap<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!dir.equals(root) && ignore.contains(dir.getFileName().toString())) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              map.put(file, new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis()));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    return map;
  }
}
