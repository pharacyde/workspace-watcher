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

  record Stamp(long size, long modified) {}

  /** What moved between two snapshots; the three lists are disjoint. */
  record Diff(List<Path> created, List<Path> modified, List<Path> deleted) {

    int total() {
      return created.size() + modified.size() + deleted.size();
    }

    boolean isEmpty() {
      return total() == 0;
    }
  }

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
      rebase(root);
    }
    if (System.currentTimeMillis() < nextScanAt) {
      return;
    }
    long startedAt = System.nanoTime();
    try {
      round(root, startedAt);
    } catch (IOException e) {
      log.debug("workspace scan failed: {}", e.toString());
    }
    pace(startedAt);
  }

  /** Workspace changed underneath us; the old snapshot describes a different tree entirely. */
  private void rebase(Path root) {
    previous = null;
    baselineFor = root;
    nextScanAt = 0;
    noticedSlow = false;
    // Belongs to the tree that was left behind. Kept, it would label the first ordinary save in
    // the returned-to workspace as a live log, and it would grow with every switch.
    growthRuns.clear();
  }

  /** One round: snapshot, notice a slow tree once, then either baseline or diff and publish. */
  private void round(Path root, long startedAt) throws IOException {
    Map<Path, Stamp> current = snapshot(root);
    long walkMs = (System.nanoTime() - startedAt) / 1_000_000;
    noticeSlowScan(root, current.size(), walkMs);

    Map<Path, Stamp> before = previous;
    previous = current;
    if (before == null) {
      // First pass only establishes the baseline; replaying the whole tree as "created"
      // would bury the session's real activity.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.SYSTEM, "BASELINE")
              .summary("watching " + root + " (" + current.size() + " files)")
              .path(root.toString()));
      git.refresh();
      return;
    }

    Diff diff = diff(before, current);
    trackGrowth(before, current, diff);
    if (diff.isEmpty()) {
      // Nothing in the tree moved, but git itself may have: a commit leaves every file exactly as
      // it was, and without this the working tree panel kept listing what had just been committed.
      git.refreshIfGitChanged();
      return;
    }
    publish(root, diff);
    git.refresh();
  }

  /**
   * Says once, out loud, that the file layer on this tree is coarse.
   *
   * <p>Judged on the walk alone, which is the interval the duty cycle would derive from it; the
   * actual pacing is set from the whole round in {@link #pace}. Agent attribution is unaffected -
   * that comes from transcripts and hooks, which are cheap and exact.
   */
  private void noticeSlowScan(Path root, int files, long walkMs) {
    long interval = Math.max(props.getFsPollMs(), walkMs * DUTY_CYCLE_DIVISOR);
    if (interval <= SLOW_SCAN_NOTICE_MS || noticedSlow) {
      return;
    }
    noticedSlow = true;
    bus.publish(
        WatchEvent.of(WatchEvent.Source.SYSTEM, "SLOW_SCAN")
            .summary(
                files
                    + " files take "
                    + walkMs
                    + "ms to scan; file events will lag by up to "
                    + interval / 1000
                    + "s")
            .path(root.toString()));
  }

  /** What changed between two snapshots, by size and mtime; pure so it can be tested as such. */
  static Diff diff(Map<Path, Stamp> before, Map<Path, Stamp> after) {
    List<Path> created = new ArrayList<>();
    List<Path> modified = new ArrayList<>();
    List<Path> deleted = new ArrayList<>();
    for (Map.Entry<Path, Stamp> entry : after.entrySet()) {
      Stamp was = before.get(entry.getKey());
      if (was == null) {
        created.add(entry.getKey());
      } else if (!was.equals(entry.getValue())) {
        modified.add(entry.getKey());
      }
    }
    for (Path gone : before.keySet()) {
      if (!after.containsKey(gone)) {
        deleted.add(gone);
      }
    }
    return new Diff(created, modified, deleted);
  }

  /**
   * Keeps the growth runs current: a modified file's run grows or resets with its size, and any
   * other file has stopped being written to, so it is no longer a live log.
   */
  private void trackGrowth(Map<Path, Stamp> before, Map<Path, Stamp> after, Diff diff) {
    growthRuns.keySet().retainAll(new HashSet<>(diff.modified()));
    for (Path file : diff.modified()) {
      if (after.get(file).size() > before.get(file).size()) {
        growthRuns.merge(file, 1, Integer::sum);
      } else {
        growthRuns.put(file, 0);
      }
    }
  }

  private void publish(Path root, Diff diff) {
    if (diff.total() > props.getMaxFileEventsPerScan()) {
      // Collapsed rather than listed. Thousands of rows would evict the agent's own actions from
      // the replay buffer, which is the one thing a reader actually came for.
      bus.publish(
          WatchEvent.of(WatchEvent.Source.FS, "BULK")
              .summary(
                  diff.total()
                      + " files changed at once ("
                      + diff.created().size()
                      + " created, "
                      + diff.modified().size()
                      + " modified, "
                      + diff.deleted().size()
                      + " deleted)")
              .path(root.toString()));
      return;
    }
    diff.created().forEach(file -> emit("CREATED", root, file));
    // APPENDED rather than MODIFIED for a file that keeps growing: it is the same fact with the
    // part that matters kept, and the reader can then see at a glance which row is a log being
    // written right now and worth opening to follow.
    diff.modified().forEach(file -> emit(appending(file) ? "APPENDED" : "MODIFIED", root, file));
    diff.deleted().forEach(file -> emit("DELETED", root, file));
  }

  /**
   * Sets when the next round may start, from what this one actually cost.
   *
   * <p>Measured from the start of the walk to after git has answered, so everything the round does
   * is inside the duty cycle. Pacing on the walk alone was the bug this replaces: `git.refresh()`
   * starts five git processes, measured at 57ms in this repository and more on a large one, so the
   * scanner kept its promised tenth of a core for the part it measured and spent whatever git asked
   * on top.
   */
  private void pace(long startedAt) {
    if (props.getFsPollMs() <= 0) {
      // Zero means "scan when told to", which is how the tests drive this by hand: they call
      // scan() several times in a row and expect each call to do a round. Pacing off the measured
      // round would otherwise make the second call a no-op for as long as git took.
      nextScanAt = 0;
      return;
    }
    long roundMs = (System.nanoTime() - startedAt) / 1_000_000;
    nextScanAt =
        System.currentTimeMillis() + Math.max(props.getFsPollMs(), roundMs * DUTY_CYCLE_DIVISOR);
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
