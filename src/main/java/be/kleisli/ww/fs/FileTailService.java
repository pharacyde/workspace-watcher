package be.kleisli.ww.fs;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Reads a file in the workspace, and keeps reading it while it grows.
 *
 * <p>Two questions with one answer: what is in this file, and what is being written to it now. A
 * build log is only interesting live, and a source file is only interesting whole, but the caller
 * should not have to know which it is holding before it asks.
 *
 * <p>Reading only. Nothing here writes, locks or truncates anything an agent is using.
 */
@Service
public class FileTailService {

  private static final Logger log = LoggerFactory.getLogger(FileTailService.class);

  /** How much of an existing file to show before following it. */
  private static final int TAIL_BYTES = 256 * 1024;

  /** Cap on one appended chunk, so a process dumping megabytes cannot flood a browser. */
  private static final int CHUNK_LIMIT = 512 * 1024;

  private static final Duration POLL = Duration.ofMillis(400);

  /**
   * A piece of a file.
   *
   * @param reset the caller should discard what it had: this is the first chunk, or the file was
   *     truncated or replaced and what came before no longer describes it
   * @param truncated the file was longer than what is shown, and this starts mid-file
   */
  public record Chunk(
      String path, String text, boolean reset, boolean truncated, boolean gone, boolean binary) {}

  private static Chunk absent(String path) {
    return new Chunk(path, "", true, false, true, false);
  }

  private final ActiveWorkspace active;
  private final WatcherProperties props;

  public FileTailService(ActiveWorkspace active, WatcherProperties props) {
    this.active = active;
    this.props = props;
  }

  /**
   * Resolves a workspace-relative path, refusing anything that leaves the workspace.
   *
   * <p>The server is loopback-only, but "only I can reach it" is not a reason to serve {@code
   * ../../.ssh/id_rsa} to whatever asked. Returns null rather than throwing: a path that does not
   * belong to this workspace is an ordinary answer of "nothing here", not an error.
   */
  Path resolve(String relativePath) {
    Path workspace = active.get();
    if (workspace == null || relativePath == null || relativePath.isBlank()) {
      return null;
    }
    Path root = workspace.toAbsolutePath().normalize();
    Path file = root.resolve(relativePath).normalize();
    return file.startsWith(root) ? file : null;
  }

  /**
   * The file's content now, then everything appended to it from that point on.
   *
   * <p>Polled rather than watched: a WatchService gives no offset, so a change still means reading
   * from where we were, and one poll of one file is a stat. The offset advances only to the last
   * newline in what was read, or a half-written line would be decoded as broken UTF-8 - the same
   * rule the transcript tail learned.
   */
  public Flux<Chunk> follow(String relativePath) {
    Path file = resolve(relativePath);
    if (file == null) {
      return Flux.just(absent(relativePath));
    }
    AtomicLong offset = new AtomicLong(-1);
    return Flux.concat(
            Flux.defer(() -> Flux.just(open(relativePath, file, offset))),
            // handle rather than map-then-filter: a poll that found nothing new has nothing to
            // emit, and Reactor forbids a null from map - it throws, which onErrorResume below
            // then reported as "the file is gone". Every quiet file said it had vanished after
            // one poll.
            Flux.interval(POLL, Schedulers.boundedElastic())
                .handle(
                    (tick, sink) -> {
                      Chunk chunk = more(relativePath, file, offset);
                      if (chunk != null) {
                        sink.next(chunk);
                      }
                    }))
        // A file that is not there is not going to be there on the next poll either. Without
        // this the subscription reported its absence 2.5 times a second for as long as the
        // panel stayed open - measured at five chunks in two seconds.
        .takeUntil(chunk -> chunk.gone() || chunk.binary())
        .onErrorResume(
            e -> {
              // At warn, not debug: this ends the subscription, so the panel stops updating and
              // the reason should not be invisible.
              log.warn("stopped following {}: {}", file, e.toString());
              return Flux.just(absent(relativePath));
            });
  }

  /** The tail end of the file as it stands, and the offset to continue from. */
  private Chunk open(String relativePath, Path file, AtomicLong offset) {
    try {
      if (!Files.isRegularFile(file)) {
        return absent(relativePath);
      }
      long length = Files.size(file);
      if (looksBinary(file)) {
        // Every file in the tree produces feed rows, so a .png or a .jar is one click away. Sending
        // half a megabyte of replacement characters to be rendered in a <pre> helps nobody.
        return new Chunk(relativePath, "", true, false, false, true);
      }
      if (length > props.getMaxDiffBytes()) {
        // Deliberately still readable: for a log the end is the part anyone wants, and refusing
        // outright would make the panel useless for exactly the biggest build.
        return tailOf(relativePath, file, length, offset);
      }
      byte[] all;
      try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
        all = new byte[(int) length];
        raf.readFully(all);
      }
      offset.set(length);
      return new Chunk(
          relativePath, new String(all, StandardCharsets.UTF_8), true, false, false, false);
    } catch (IOException e) {
      log.debug("cannot read {}: {}", file, e.toString());
      return absent(relativePath);
    }
  }

  /**
   * Whether the first few kilobytes contain a NUL byte.
   *
   * <p>The same test {@code git} uses, and for the same reason: it is cheap, it needs no library,
   * and it is right about every format anyone will actually click on here.
   */
  private static boolean looksBinary(Path file) {
    try (java.io.InputStream in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(8 * 1024);
      for (byte b : head) {
        if (b == 0) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      return false;
    }
  }

  private Chunk tailOf(String relativePath, Path file, long length, AtomicLong offset)
      throws IOException {
    long from = Math.max(0, length - TAIL_BYTES);
    byte[] bytes = new byte[(int) (length - from)];
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      raf.seek(from);
      raf.readFully(bytes);
    }
    offset.set(length);
    // Start at a line boundary: cutting into the middle of one leaves a broken first line, and
    // cutting into the middle of a character leaves a broken decode.
    int start = 0;
    while (start < bytes.length && bytes[start] != '\n') {
      start++;
    }
    if (start < bytes.length) {
      start++;
    }
    String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    return new Chunk(relativePath, text, true, from > 0, false, false);
  }

  /**
   * The index of the last byte that completes a UTF-8 character, or -1 if none does.
   *
   * <p>Continuation bytes are {@code 10xxxxxx}; walking back over them lands on the lead byte of
   * the character that was cut, and everything before it decodes cleanly.
   */
  private static int lastCompleteCharacter(byte[] chunk) {
    int i = chunk.length - 1;
    while (i >= 0 && (chunk[i] & 0xC0) == 0x80) {
      i--;
    }
    // i is now the lead byte of a possibly incomplete character, so keep everything before it.
    return i - 1;
  }

  /** Whatever has been appended since the last look, or null when nothing has. */
  private Chunk more(String relativePath, Path file, AtomicLong offset) {
    try {
      if (!Files.isRegularFile(file)) {
        return absent(relativePath);
      }
      long length = Files.size(file);
      long known = offset.get();
      if (length == known) {
        return null;
      }
      if (length < known) {
        // Truncated or rotated: what the caller is holding describes a file that no longer exists.
        offset.set(-1);
        return open(relativePath, file, offset);
      }
      int want = (int) Math.min(length - known, CHUNK_LIMIT);
      byte[] chunk = new byte[want];
      try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
        raf.seek(known);
        raf.readFully(chunk);
      }
      int lastNewline = -1;
      for (int i = chunk.length - 1; i >= 0; i--) {
        if (chunk[i] == '\n') {
          lastNewline = i;
          break;
        }
      }
      if (lastNewline < 0) {
        // A single line longer than the chunk limit would otherwise never be delivered. Cut at a
        // character boundary rather than at an arbitrary byte: splitting a multi-byte character
        // across two chunks is the exact failure the newline rule above exists to avoid, and a
        // progress bar written with \r is a real file with no newline in half a megabyte.
        if (want < CHUNK_LIMIT) {
          return null;
        }
        lastNewline = lastCompleteCharacter(chunk);
        if (lastNewline < 0) {
          return null;
        }
      }
      offset.set(known + lastNewline + 1);
      return new Chunk(
          relativePath,
          new String(chunk, 0, lastNewline + 1, StandardCharsets.UTF_8),
          false,
          false,
          false,
          false);
    } catch (IOException e) {
      log.debug("cannot follow {}: {}", file, e.toString());
      return null;
    }
  }
}
