package be.kleisli.ww.fs;

import static org.assertj.core.api.Assertions.assertThat;

import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.WatcherProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class FileTailServiceTest {

  @TempDir Path tmp;

  private Path workspace;
  private FileTailService service;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(tmp.resolve("project"));
    WatcherProperties props = new WatcherProperties();
    props.setWorkspace(workspace.toString());
    service = new FileTailService(new ActiveWorkspace(props), props);
  }

  private void append(Path file, String text) throws IOException {
    Files.writeString(
        file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  @Test
  @DisplayName("sends a file that never changes in one chunk and then waits")
  void readsAStaticFile() throws IOException {
    Path file = workspace.resolve("notes.txt");
    append(file, "first line\nsecond line\n");

    StepVerifier.create(service.follow("notes.txt").take(1))
        .assertNext(
            chunk -> {
              assertThat(chunk.text()).isEqualTo("first line\nsecond line\n");
              assertThat(chunk.reset()).isTrue();
              assertThat(chunk.gone()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  @DisplayName("keeps sending what is appended to a log")
  void followsAGrowingFile() throws IOException {
    Path file = workspace.resolve("build.log");
    append(file, "starting\n");

    StepVerifier.create(service.follow("build.log").take(2))
        .assertNext(chunk -> assertThat(chunk.text()).isEqualTo("starting\n"))
        .then(
            () -> {
              try {
                append(file, "still going\n");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            })
        .assertNext(
            chunk -> {
              // Only what is new, and marked as an addition rather than a replacement.
              assertThat(chunk.text()).isEqualTo("still going\n");
              assertThat(chunk.reset()).isFalse();
            })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("holds back a line the writer has not finished")
  void waitsForTheNewline() throws IOException {
    Path file = workspace.resolve("partial.log");
    append(file, "complete\n");

    StepVerifier.create(service.follow("partial.log").take(2))
        .assertNext(chunk -> assertThat(chunk.text()).isEqualTo("complete\n"))
        .then(
            () -> {
              try {
                // Half a line, as a writer flushing mid-line leaves it. Sending it would split a
                // UTF-8 character across two chunks and render as replacement characters.
                append(file, "half a li");
                Thread.sleep(900);
                append(file, "ne\n");
              } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
              }
            })
        .assertNext(chunk -> assertThat(chunk.text()).isEqualTo("half a line\n"))
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("starts again when a log is rotated out from under it")
  void resetsOnTruncation() throws IOException {
    Path file = workspace.resolve("rotate.log");
    append(file, "old and long enough to shrink from\n");

    StepVerifier.create(service.follow("rotate.log").take(2))
        .assertNext(chunk -> assertThat(chunk.reset()).isTrue())
        .then(
            () -> {
              try {
                Files.writeString(file, "new\n");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            })
        .assertNext(
            chunk -> {
              // What the caller is holding describes a file that no longer exists, so it is told
              // to drop it rather than being sent an append that would read as nonsense.
              assertThat(chunk.reset()).isTrue();
              assertThat(chunk.text()).isEqualTo("new\n");
            })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("refuses a path that leads out of the workspace")
  void refusesEscapingPaths() {
    // Loopback-only is not a reason to serve whatever is asked for.
    assertThat(service.resolve("../../etc/passwd")).isNull();
    assertThat(service.resolve("sub/../../outside.txt")).isNull();
    assertThat(service.resolve("inside.txt")).isEqualTo(workspace.resolve("inside.txt"));

    StepVerifier.create(service.follow("../secret").take(1))
        .assertNext(chunk -> assertThat(chunk.gone()).isTrue())
        .verifyComplete();
  }

  @Test
  @DisplayName("says a file is missing once, and then stops")
  void reportsAMissingFileOnce() {
    // No take(1) here, deliberately: with one the test passed while the subscription went on
    // repeating itself 2.5 times a second for as long as a panel stayed open. Clicking a DELETED
    // row in the feed was enough to trigger it.
    List<FileTailService.Chunk> chunks =
        service.follow("nothing-here.txt").take(Duration.ofSeconds(2)).collectList().block();

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).gone()).isTrue();
  }

  @Test
  @DisplayName("stops following a file that is deleted underneath it")
  void stopsWhenTheFileDisappears() throws IOException {
    Path file = workspace.resolve("doomed.log");
    append(file, "here for now\n");

    List<FileTailService.Chunk> chunks =
        service
            .follow("doomed.log")
            .doOnNext(
                chunk -> {
                  if (!chunk.gone()) {
                    try {
                      Files.deleteIfExists(file);
                    } catch (IOException e) {
                      throw new IllegalStateException(e);
                    }
                  }
                })
            .take(Duration.ofSeconds(3))
            .collectList()
            .block();

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(1).gone()).isTrue();
  }

  @Test
  @DisplayName("declines a file that is not text")
  void declinesBinaryFiles() throws IOException {
    Path file = workspace.resolve("logo.png");
    Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});

    List<FileTailService.Chunk> chunks =
        service.follow("logo.png").take(Duration.ofSeconds(2)).collectList().block();

    // Every file in the tree produces feed rows, so an image is one click away.
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).binary()).isTrue();
    assertThat(chunks.get(0).text()).isEmpty();
  }

  @Test
  @DisplayName("never splits a character across two chunks, even without a newline")
  void cutsLongLinesAtACharacterBoundary() throws IOException {
    Path file = workspace.resolve("progress.log");
    append(file, "start\n");

    StepVerifier.create(service.follow("progress.log").take(2))
        .assertNext(chunk -> assertThat(chunk.text()).isEqualTo("start\n"))
        .then(
            () -> {
              try {
                // Over the chunk limit with no newline anywhere, all multi-byte: a progress bar
                // drawn with \r looks exactly like this. Cutting at an arbitrary byte would split a
                // character and decode as replacement characters.
                append(file, "é".repeat(400_000));
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            })
        .assertNext(
            chunk -> {
              assertThat(chunk.text()).doesNotContain("\uFFFD");
              assertThat(chunk.text()).isNotEmpty();
              assertThat(chunk.text().chars().allMatch(c -> c == 'é')).isTrue();
            })
        .expectComplete()
        .verify(Duration.ofSeconds(15));
  }

  @Test
  @DisplayName("shows the end of a file too large to send whole")
  void tailsALargeFile() throws IOException {
    Path file = workspace.resolve("huge.log");
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 60_000; i++) {
      big.append("line ").append(i).append('\n');
    }
    append(file, big.toString());
    assertThat(Files.size(file)).isGreaterThan(256 * 1024);

    StepVerifier.create(service.follow("huge.log").take(1))
        .assertNext(
            chunk -> {
              assertThat(chunk.truncated()).isTrue();
              assertThat(chunk.text()).endsWith("line 59999\n");
              // Cut at a line boundary, so the first visible line is a whole one.
              assertThat(chunk.text().lines().findFirst().orElseThrow()).startsWith("line ");
            })
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("does not resend a file nothing is writing to")
  void staysQuietWhenNothingChanges() throws IOException {
    Path file = workspace.resolve("still.txt");
    append(file, "unchanged\n");

    List<FileTailService.Chunk> chunks =
        service.follow("still.txt").take(Duration.ofSeconds(2)).collectList().block();

    assertThat(chunks).hasSize(1);
  }
}
