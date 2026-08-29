package be.kleisli.ww.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShellTest {

  @Test
  @DisplayName("returns what a command wrote")
  void readsOutput() {
    Shell.Result result = Shell.run(null, List.of("echo", "hello"), 5);

    assertThat(result.ok()).isTrue();
    assertThat(result.stdout()).startsWith("hello");
  }

  @Test
  @DisplayName("a command that never finishes is killed, and the caller is not left waiting")
  void boundsACommandThatHangs() {
    // The timeout used to be unreachable: readAllBytes waits for EOF on stdout, and a process that
    // holds the pipe open never gives it - so waitFor(timeout) was never reached. `sleep` keeps the
    // descriptor open for as long as it runs, which is exactly that shape.
    Instant start = Instant.now();

    Shell.Result result = Shell.run(null, List.of("sleep", "30"), 1);

    Duration waited = Duration.between(start, Instant.now());
    assertThat(waited).isLessThan(Duration.ofSeconds(10));
    // Killed, so it is a failure and not an empty success anyone would act on.
    assertThat(result.ok()).isFalse();
  }
}
