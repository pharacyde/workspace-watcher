package be.kleisli.ww.proc;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.store.EventStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Which processes are currently working inside the workspace.
 *
 * <p>Found by asking {@code lsof} for every process's current working directory in one call and
 * keeping those rooted in the workspace, then reconstructing parent/child links through {@link
 * ProcessHandle}. That is cheap and needs no privileges.
 *
 * <p>Known limitation, stated plainly: this is a sampler. A {@code git status} that lives forty
 * milliseconds will usually fall between two polls, and a process whose cwd is elsewhere is
 * invisible here even if it writes into the workspace. For a complete record of what an agent ran,
 * trust the transcript and hook events, not this panel.
 */
@Service
public class ProcessTreeService {

  public record Node(long pid, String command, String cwd, List<Node> children) {}

  /**
   * A file a process is holding open.
   *
   * <p>{@code relativePath} is set only for a file inside the workspace, and it is what makes the
   * row clickable: the tail subscription takes a workspace-relative path and refuses anything
   * outside, which is the same rule the rest of this app is served under. A file elsewhere is
   * listed with its absolute path and nothing else, because saying it is there is honest and
   * serving its contents over a port would not be.
   */
  public record OpenFile(String fd, String mode, String path, String relativePath) {}

  /** Wrapper so the subscription has a single named type to return. */
  public record Snapshot(String at, int total, List<Node> roots) {}

  private final WatcherProperties props;
  private final ActiveWorkspace active;
  private final EventStore store;
  private final StateStream<Snapshot> stream = new StateStream<>();
  private volatile List<Node> lastTree = List.of();

  public ProcessTreeService(WatcherProperties props, ActiveWorkspace active, EventStore store) {
    this.props = props;
    this.active = active;
    this.store = store;
    stream.publish(new Snapshot(Instant.now().toString(), 0, List.of()));
  }

  public Snapshot currentSnapshot() {
    return stream.current();
  }

  public StateStream<Snapshot> stream() {
    return stream;
  }

  /**
   * Share of wall-clock time this sampler may spend in {@code lsof}, as the scanner does for its
   * walk.
   *
   * <p>Measured on this machine, one {@code lsof -a -d cwd -F pn} costs 110ms. At the configured
   * two seconds that is 5.5% of a core, permanently - more than the whole of the rest of the
   * watcher, which measured 1.1% while idle - and it does not show up in the watcher's own CPU
   * figure at all, because the cost is charged to a child process. On a busier machine, where lsof
   * takes a second, the fixed interval would have spent half a core.
   */
  private static final long DUTY_CYCLE_DIVISOR = 10;

  /**
   * How much slower to sample while no panel is subscribed.
   *
   * <p>The process tree is state, not a chronicle: nothing is lost by not looking, and a panel gets
   * the current value the moment it connects. The resource series keeps being written either way,
   * only coarser - which is the honest trade, because the alternative is spending a twentieth of a
   * core on a question nobody is asking.
   */
  private static final long IDLE_MULTIPLIER = 5;

  private long nextPollAt;

  @Scheduled(fixedDelay = 250)
  public void poll() {
    Path workspace = active.get();
    if (props.getProcessPollMs() <= 0 || workspace == null) {
      return;
    }
    if (System.currentTimeMillis() < nextPollAt) {
      return;
    }
    long startedAt = System.nanoTime();
    try {
      round(workspace);
    } finally {
      long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
      long base = props.getProcessPollMs() * (stream.subscribers() == 0 ? IDLE_MULTIPLIER : 1);
      nextPollAt = System.currentTimeMillis() + Math.max(base, tookMs * DUTY_CYCLE_DIVISOR);
    }
  }

  private void round(Path workspace) {
    Map<Long, String> inWorkspace = processesWithCwdIn(workspace);

    // Sampled every poll, before the tree is compared. A steady build keeps the same processes for
    // minutes, so the tree does not change - and that is exactly when CPU is worth a look. Behind
    // the equality check, the series stayed empty during the only periods anyone would examine it.
    sampleResources(inWorkspace.keySet(), workspace);

    List<Node> tree = buildTree(inWorkspace);
    if (tree.equals(lastTree)) {
      return;
    }
    lastTree = tree;
    stream.publish(new Snapshot(Instant.now().toString(), count(tree), tree));
  }

  /**
   * The regular files one process currently has open.
   *
   * <p>Asked per process and only when someone clicks, rather than sampled: {@code lsof -p} is one
   * call about one process, and the answer is only interesting while a panel is showing it.
   *
   * <p>Numeric descriptors only. A process holds its executable, every shared library and the
   * locale files open as well - {@code txt} and {@code mem} - which is a hundred rows of noise
   * around the handful anyone means by "what does it have open". Descriptor 1 or 2 pointing at a
   * file is exactly the case worth clicking: that is where the log is being written.
   */
  public List<OpenFile> openFiles(long pid) {
    // Only a process this panel is already showing. Without the check, anything that can reach the
    // port could walk pids 1..N and read the paths of every file every process on the machine has
    // open - a browser profile, a password store. This app is loopback-only and unauthenticated,
    // and invariant 4 puts source diffs and command lines in that trade, not the whole machine.
    if (!isWatched(pid, currentSnapshot().roots())) {
      return List.of();
    }
    // Bounded like every other shell-out here. lsof can block on a stale network mount, and a
    // dashboard query is not allowed to hang because of one.
    Shell.Result result =
        Shell.run(null, List.of("lsof", "-p", String.valueOf(pid), "-F", "fatn"), 5);
    return parseOpenFiles(result.lines(), active.get());
  }

  /** Whether a pid appears anywhere in the tree that was last published. */
  static boolean isWatched(long pid, List<Node> nodes) {
    return nodes.stream().anyMatch(node -> node.pid() == pid || isWatched(pid, node.children()));
  }

  /** Package-private so the parsing can be tested without running lsof. */
  static List<OpenFile> parseOpenFiles(List<String> lines, Path workspace) {
    List<String> prefixes = prefixesFor(workspace);
    List<OpenFile> files = new ArrayList<>();
    String fd = null;
    String mode = null;
    String type = null;
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      char field = line.charAt(0);
      String value = line.substring(1);
      switch (field) {
        case 'f' -> {
          // A new descriptor begins; whatever was collected for the previous one is complete.
          fd = value;
          mode = null;
          type = null;
        }
        case 'a' -> mode = value;
        case 't' -> type = value;
        case 'n' -> {
          if (fd != null && "REG".equals(type) && fd.chars().allMatch(Character::isDigit)) {
            files.add(
                new OpenFile(
                    fd,
                    // lsof writes a space when it cannot tell the access mode; that is "unknown",
                    // and rendering it as a character shifted the column by one.
                    mode == null ? "" : mode.trim(),
                    value,
                    relativeTo(prefixes, value)));
          }
          fd = null;
        }
        default -> {
          // p (the process), and everything else lsof volunteers, is not part of a file record.
        }
      }
    }
    return List.copyOf(files);
  }

  /**
   * The path relative to the workspace, or null when the file is not in it.
   *
   * <p>The boundary matters: a bare startsWith makes /Users/me/Dev2 a file of /Users/me/Dev, which
   * is the same bug this project has already had twice.
   */
  private static String relativeTo(List<String> prefixes, String path) {
    for (String prefix : prefixes) {
      if (path.startsWith(prefix + "/")) {
        return path.substring(prefix.length() + 1);
      }
    }
    return null;
  }

  /** Whether a path lsof reported sits inside the workspace, under any of its names. */
  private static boolean inside(String path, List<String> prefixes) {
    return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  /**
   * The names the workspace answers to: as configured, and as the filesystem really spells it.
   *
   * <p>lsof reports resolved paths, and a workspace reached through a symlink is not spelled the
   * same way - on macOS /tmp is a link to /private/tmp, so a workspace under it matched nothing at
   * all and this panel sat empty with no error to explain it. Found by the browser test, which runs
   * in exactly such a directory; every unit test here uses a path that does not exist, where
   * toRealPath fails and the configured name is the only one, which is why they never saw it.
   */
  private static List<String> prefixesFor(Path workspace) {
    if (workspace == null) {
      return List.of();
    }
    String configured = workspace.toString();
    try {
      String real = workspace.toRealPath().toString();
      return real.equals(configured) ? List.of(configured) : List.of(configured, real);
    } catch (java.io.IOException | RuntimeException e) {
      return List.of(configured);
    }
  }

  /** One {@code lsof} call for all processes, filtered to the workspace subtree. */
  private Map<Long, String> processesWithCwdIn(Path workspace) {
    Shell.Result result = Shell.run(null, List.of("lsof", "-a", "-d", "cwd", "-F", "pn"), 20);
    return parseCwdLines(result.lines(), workspace);
  }

  /**
   * Parses {@code lsof -F pn} output: a {@code p<pid>} line followed by an {@code n<path>} line.
   *
   * <p>Package-private so the parsing can be tested without running lsof.
   */
  static Map<Long, String> parseCwdLines(List<String> lines, Path workspace) {
    Map<Long, String> matched = new LinkedHashMap<>();
    Long pid = null;
    List<String> prefixes = prefixesFor(workspace);
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      char field = line.charAt(0);
      String value = line.substring(1);
      if (field == 'p') {
        pid = parse(value);
      } else if (field == 'n' && pid != null) {
        if (inside(value, prefixes)) {
          matched.put(pid, value);
        }
        pid = null;
      }
    }
    return matched;
  }

  private List<Node> buildTree(Map<Long, String> matched) {
    Map<Long, List<Node>> childrenOf = new LinkedHashMap<>();
    List<Node> roots = new ArrayList<>();
    for (Map.Entry<Long, String> entry : matched.entrySet()) {
      long pid = entry.getKey();
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      String command =
          handle
              .map(h -> h.info().commandLine().orElse(h.info().command().orElse("(unknown)")))
              .orElse("(gone)");
      Node node =
          new Node(
              pid,
              command,
              entry.getValue(),
              childrenOf.computeIfAbsent(pid, k -> new ArrayList<>()));

      long parent = handle.flatMap(ProcessHandle::parent).map(ProcessHandle::pid).orElse(-1L);
      if (matched.containsKey(parent) && parent != pid) {
        childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
      } else {
        roots.add(node);
      }
    }
    return roots;
  }

  /**
   * Total CPU and memory of everything working in this workspace.
   *
   * <p>One {@code ps} call for the whole set. Per-process GPU and Neural Engine figures are not
   * here because they are not obtainable: {@code powermetrics} refuses to run without root, and
   * even with it reports system-wide numbers rather than per-process ones.
   */
  private void sampleResources(Set<Long> pids, Path workspace) {
    if (pids.isEmpty()) {
      store.recordResources(workspace.toString(), 0, 0);
      return;
    }
    List<String> command = new ArrayList<>(List.of("ps", "-o", "%cpu=,rss=", "-p"));
    command.add(pids.stream().map(String::valueOf).collect(Collectors.joining(",")));

    double cpu = 0;
    long rssKb = 0;
    for (String line : Shell.run(null, command, 10).lines()) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length < 2) {
        continue;
      }
      try {
        cpu += Double.parseDouble(fields[0]);
        rssKb += Long.parseLong(fields[1]);
      } catch (NumberFormatException e) {
        // A row that vanished between lsof and ps is not worth a log line.
      }
    }
    store.recordResources(workspace.toString(), cpu, rssKb);
  }

  private static int count(List<Node> nodes) {
    int total = 0;
    for (Node node : nodes) {
      total += 1 + count(node.children());
    }
    return total;
  }

  private static Long parse(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
