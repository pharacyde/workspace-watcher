package be.kleisli.ww.claude;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.StateStream;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The register of workspaces an agent has actually worked in.
 *
 * <p>Registration is not something anyone configures. The hook writes into a spool directory named
 * after the project and drops a {@code .workspace} marker holding the real path the first time it
 * fires, so a project enrols itself the moment an agent does anything in it. Installed globally,
 * the hook therefore registers every project you touch, and the register survives a restart because
 * it lives on disk rather than in memory.
 *
 * <p>The marker exists because the directory name is escaped and cannot be turned back into a path.
 *
 * <p>When nothing is being watched yet, the most recently active entry is adopted. That is the
 * whole point: start the watcher with no arguments, work as usual, and it follows you.
 */
@Service
public class WorkspaceRegistry {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceRegistry.class);

  /** A workspace that has registered itself, and how recently it was active. */
  public record Entry(String path, String lastActivity, int pendingEvents, boolean exists) {}

  private final WatcherProperties props;
  private final ActiveWorkspace active;
  private final StateStream<List<Entry>> stream = new StateStream<>();

  public WorkspaceRegistry(WatcherProperties props, ActiveWorkspace active) {
    this.props = props;
    this.active = active;
    stream.publish(List.of());
  }

  public List<Entry> current() {
    return stream.current();
  }

  public StateStream<List<Entry>> stream() {
    return stream;
  }

  @Scheduled(fixedDelayString = "${watcher.registry-poll-ms:2000}")
  public void scan() {
    List<Entry> entries = read();
    if (!entries.equals(stream.current())) {
      stream.publish(entries);
    }
    adoptIfIdle(entries);
  }

  /** Directory name for a workspace: every non-alphanumeric character becomes a dash. */
  public Path spoolFor(Path workspace) {
    return props.spoolBasePath().resolve(workspace.toString().replaceAll("[^a-zA-Z0-9]", "-"));
  }

  private List<Entry> read() {
    Path base = props.spoolBasePath();
    if (!Files.isDirectory(base)) {
      return List.of();
    }
    List<Entry> entries = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(base)) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        Path marker = dir.resolve(".workspace");
        if (!Files.isRegularFile(marker)) {
          continue;
        }
        try {
          String path = Files.readString(marker, StandardCharsets.UTF_8).strip();
          if (path.isEmpty()) {
            continue;
          }
          entries.add(
              new Entry(
                  path,
                  Files.getLastModifiedTime(dir).toInstant().toString(),
                  countPending(dir),
                  Files.isDirectory(Path.of(path))));
        } catch (IOException e) {
          log.debug("cannot read marker {}: {}", marker, e.toString());
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    entries.sort(Comparator.comparing(Entry::lastActivity).reversed());
    return entries;
  }

  private int countPending(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return (int) files.filter(f -> f.getFileName().toString().endsWith(".json")).count();
    } catch (IOException e) {
      return 0;
    }
  }

  /** With nothing being watched, follow whichever project was touched most recently. */
  private void adoptIfIdle(List<Entry> entries) {
    if (active.isSet() || entries.isEmpty()) {
      return;
    }
    entries.stream()
        .filter(Entry::exists)
        .max(Comparator.comparing(entry -> Instant.parse(entry.lastActivity())))
        .ifPresent(
            entry -> {
              try {
                active.set(Path.of(entry.path()));
              } catch (RuntimeException e) {
                log.debug("cannot adopt {}: {}", entry.path(), e.toString());
              }
            });
  }
}
