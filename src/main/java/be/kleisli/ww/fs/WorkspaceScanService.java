package be.kleisli.ww.fs;

import be.kleisli.ww.core.ActiveWorkspace;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
  private final ActiveWorkspace active;
  private final EventBus bus;
  private final GitService git;
  private final Set<String> ignore;

  /**
   * Share of wall-clock time this scanner may spend walking the tree.
   *
   * <p>Fixed at a tenth. The interval is derived from how long a walk actually takes rather than
   * assumed: measured here, a 66,000-file tree takes 0.54s to walk, so polling it every 750ms as
   * configured meant scanning essentially without pause and cost 30-85% of a core. Workspaces are
   * discovered now, not configured, so landing on a large tree is a normal accident rather than
   * user error.
   */
  private static final long DUTY_CYCLE_DIVISOR = 10;

  /** Effective interval above which the file layer is coarse enough to be worth mentioning. */
  private static final long SLOW_SCAN_NOTICE_MS = 5_000;

  private Map<Path, Stamp> previous;

  /**
   * How many scans in a row a file has grown by.
   *
   * <p>Two in a row is what separates a log from a save. Every editor write is one jump in size;
   * something being appended to keeps growing while nobody touches it, and that is the file worth
   * pointing at - it is the one where following it means something.
   */
  private final Map<Path, Integer> growthRuns = new HashMap<>();

  private Path baselineFor;
  private long nextScanAt;
  private boolean noticedSlow;

  public WorkspaceScanService(
      WatcherProperties props, ActiveWorkspace active, EventBus bus, GitService git) {
    this.props = props;
    this.active = active;
    this.bus = bus;
    this.git = git;
    this.ignore = new HashSet<>(props.getIgnoreDirs());
  }

  @Scheduled(fixedDelayString = "${watcher.fs-poll-ms:750}")
  public void scan() {
    Path root = active.get();
    if (root == null || !Files.isDirectory(root)) {
      return;
    }
    if (!root.equals(baselineFor)) {
      // Workspace changed underneath us; the old snapshot describes a different tree entirely.
      previous = null;
      baselineFor = root;
      nextScanAt = 0;
      noticedSlow = false;
      // Belongs to the tree that was left behind. Kept, it would label the first ordinary save in
      // the returned-to workspace as a live log, and it would grow with every switch.
      growthRuns.clear();
    }
    if (System.currentTimeMillis() < nextScanAt) {
      return;
    }
    Map<Path, Stamp> current;
    long startedAt = System.nanoTime();
    try {
      current = snapshot(root);
    } catch (IOException e) {
      log.debug("workspace scan failed: {}", e.toString());
      return;
    }
    long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
    long interval = Math.max(props.getFsPollMs(), tookMs * DUTY_CYCLE_DIVISOR);
    nextScanAt = System.currentTimeMillis() + interval;

    if (interval > SLOW_SCAN_NOTICE_MS && !noticedSlow) {
      noticedSlow = true;
      // Said out loud rather than hidden: on a tree this size the file layer reports changes in
      // batches of seconds. Agent attribution is unaffected - that comes from transcripts and
      // hooks, which are cheap and exact.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.SYSTEM, "SLOW_SCAN")
              .summary(
                  current.size()
                      + " files take "
                      + tookMs
                      + "ms to scan; file events will lag by up to "
                      + interval / 1000
                      + "s")
              .path(root.toString()));
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

    List<Path> created = new ArrayList<>();
    List<Path> modified = new ArrayList<>();
    List<Path> deleted = new ArrayList<>();
    for (Map.Entry<Path, Stamp> entry : current.entrySet()) {
      Stamp before = previous.get(entry.getKey());
      if (before == null) {
        created.add(entry.getKey());
      } else if (!before.equals(entry.getValue())) {
        modified.add(entry.getKey());
        if (entry.getValue().size() > before.size()) {
          growthRuns.merge(entry.getKey(), 1, Integer::sum);
        } else {
          growthRuns.put(entry.getKey(), 0);
        }
      } else {
        // Unchanged: whatever was writing to it has stopped, so it is no longer a live log.
        growthRuns.remove(entry.getKey());
      }
    }
    for (Path gone : previous.keySet()) {
      if (!current.containsKey(gone)) {
        deleted.add(gone);
        growthRuns.remove(gone);
      }
    }

    previous = current;
    int total = created.size() + modified.size() + deleted.size();
    if (total == 0) {
      return;
    }

    if (total > props.getMaxFileEventsPerScan()) {
      // Collapsed rather than listed. Thousands of rows would evict the agent's own actions from
      // the replay buffer, which is the one thing a reader actually came for.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.FS, "BULK")
              .summary(
                  total
                      + " files changed at once ("
                      + created.size()
                      + " created, "
                      + modified.size()
                      + " modified, "
                      + deleted.size()
                      + " deleted)")
              .path(root.toString()));
    } else {
      created.forEach(file -> emit("CREATED", root, file));
      // APPENDED rather than MODIFIED for a file that keeps growing: it is the same fact with the
      // part that matters kept, and the reader can then see at a glance which row is a log being
      // written right now and worth opening to follow.
      modified.forEach(file -> emit(appending(file) ? "APPENDED" : "MODIFIED", root, file));
      deleted.forEach(file -> emit("DELETED", root, file));
    }
    git.refresh();
  }

  /**
   * Grown on at least two consecutive scans, so something is writing to it rather than saving it.
   */
  private boolean appending(Path file) {
    return growthRuns.getOrDefault(file, 0) >= 2;
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
            // Only ".git", and only because in a linked worktree it is a file rather than a
            // directory, so the directory filter never sees it. Applying the whole directory list
            // here would silently drop a file named "build" or "dist", which is a real thing to
            // have at a repository root.
            if (attrs.isRegularFile() && !".git".equals(file.getFileName().toString())) {
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
