package be.kleisli.ww.fs;

import be.kleisli.ww.git.GitService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * That a file changed, and nothing about what is in it.
 *
 * <p>Split from {@link FileTailService} rather than borrowed from it. A diff that keeps up needs a
 * notification, not a copy: driving it from the tail shipped the whole file over the socket - up to
 * the diff limit - and every appended chunk after that, all of it used as a boolean and thrown
 * away, on top of the read the diff itself already does.
 *
 * <p>The path is resolved the way {@link GitService#versions} resolves it, relative to the
 * repository root, because this exists to say when that answer went stale. The tail resolves
 * against the workspace instead, and the two are the same directory only when the workspace is the
 * repository root - in a subdirectory workspace the tail pointed at a file that does not exist, so
 * the notification never arrived and the diff sat there silently claiming to be live.
 */
@Service
public class FileChangeService {

  private static final Logger log = LoggerFactory.getLogger(FileChangeService.class);

  /** Matches the tail's pace: one poll of one file is a stat, and it is the same order of work. */
  private static final Duration POLL = Duration.ofMillis(400);

  /**
   * Size and modification time are what says "different"; the content is nobody's business here.
   */
  public record Change(String path, long size, String modifiedAt, boolean gone) {}

  private final GitService git;

  public FileChangeService(GitService git) {
    this.git = git;
  }

  /**
   * Emits the file's present state, then again whenever it changes.
   *
   * <p>Emitting on subscribe costs one message and saves the caller from having to decide whether
   * what it fetched a moment ago is still current.
   */
  public Flux<Change> watch(String relativePath) {
    Path file;
    try {
      file = git.resolveInRepo(relativePath);
    } catch (RuntimeException e) {
      // Outside the repository, or nothing being watched: an ordinary "nothing here" answer.
      return Flux.just(new Change(relativePath, -1, null, true));
    }
    AtomicReference<Change> last = new AtomicReference<>();
    // The left-hand side of a diff is `git show HEAD:<path>`, which moves without the file moving.
    AtomicReference<String> lastHead = new AtomicReference<>(git.current().head());
    return Flux.concat(
            Flux.defer(
                () -> {
                  Change first = record(relativePath, file, last);
                  return first == null ? Flux.<Change>empty() : Flux.just(first);
                }),
            // handle rather than map: an unchanged file has nothing to emit, and Reactor throws on
            // a null from map - the same trap the tail documents one file over.
            Flux.interval(POLL, Schedulers.boundedElastic())
                .handle(
                    (tick, sink) -> {
                      Change change = record(relativePath, file, last);
                      // A commit changes neither the size nor the modification time of the file,
                      // and it changes the diff completely: what was a page of changes becomes
                      // nothing at all. Without this the panel went on showing the differences
                      // against the previous commit, with the live badge lit over a diff that
                      // could no longer arrive. Read from the snapshot GitService already holds,
                      // so this costs no process.
                      if (change == null
                          && !Objects.equals(git.current().head(), lastHead.get())
                          && last.get() != null) {
                        change = last.get();
                      }
                      lastHead.set(git.current().head());
                      if (change != null) {
                        sink.next(change);
                      }
                    }))
        // A file that is gone is not coming back under the same subscription, and repeating that
        // 2.5 times a second is what the tail had to learn not to do.
        .takeUntil(Change::gone)
        .onErrorResume(
            e -> {
              log.warn("stopped watching {}: {}", file, e.toString());
              return Flux.just(new Change(relativePath, -1, null, true));
            });
  }

  /** The current state, or null when it is the same one as last time. */
  private static Change record(String relativePath, Path file, AtomicReference<Change> last) {
    Change now;
    try {
      if (!Files.isRegularFile(file)) {
        now = new Change(relativePath, -1, null, true);
      } else {
        now =
            new Change(
                relativePath,
                Files.size(file),
                Files.getLastModifiedTime(file).toInstant().toString(),
                false);
      }
    } catch (IOException e) {
      now = new Change(relativePath, -1, Instant.now().toString(), true);
    }
    return now.equals(last.getAndSet(now)) ? null : now;
  }
}
