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

  @Scheduled(fixedDelayString = "${watcher.process-poll-ms:2000}")
  public void poll() {
    Path workspace = active.get();
    if (props.getProcessPollMs() <= 0 || workspace == null) {
      return;
    }
    Map<Long, String> inWorkspace = processesWithCwdIn(workspace);
    List<Node> tree = buildTree(inWorkspace);
    if (tree.equals(lastTree)) {
      return;
    }
    lastTree = tree;
    stream.publish(new Snapshot(Instant.now().toString(), count(tree), tree));
    sampleResources(inWorkspace.keySet(), workspace);
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
    String prefix = workspace.toString();
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      char field = line.charAt(0);
      String value = line.substring(1);
      if (field == 'p') {
        pid = parse(value);
      } else if (field == 'n' && pid != null) {
        if (value.equals(prefix) || value.startsWith(prefix + "/")) {
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
