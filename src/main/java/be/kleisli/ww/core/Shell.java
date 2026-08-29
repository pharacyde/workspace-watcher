package be.kleisli.ww.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal, timeout-bounded process runner. Never uses a shell, so nothing gets interpreted. */
public final class Shell {

  public record Result(int exitCode, String stdout) {
    public boolean ok() {
      return exitCode == 0;
    }

    public List<String> lines() {
      return stdout.isEmpty() ? List.of() : List.of(stdout.split("\n", -1));
    }
  }

  /**
   * One daemon thread for every timeout in the process.
   *
   * <p>Daemon, so a scheduled kill that never fires cannot keep the JVM alive; and single, because
   * it does nothing but call destroyForcibly on a process that has already outstayed its welcome.
   */
  private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "shell-watchdog");
            t.setDaemon(true);
            return t;
          });

  private Shell() {}

  /**
   * Runs a command and gives up on it after {@code timeoutSeconds}.
   *
   * <p>The timeout is enforced by a watchdog that destroys the process, not by {@code waitFor}
   * alone. Reading stdout to EOF happens first - it has to, or a chatty command deadlocks on a full
   * pipe - and EOF never arrives for a process wedged on a stale network mount, so the timeout that
   * was written here was never consulted. It read as bounded and was not, which is the worst way
   * for a limit to be wrong.
   */
  public static Result run(Path workingDir, List<String> command, long timeoutSeconds) {
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
    if (workingDir != null) {
      pb.directory(workingDir.toFile());
    }
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process = null;
    java.util.concurrent.ScheduledFuture<?> watchdog = null;
    try {
      process = pb.start();
      process.getOutputStream().close();
      Process started = process;
      watchdog = WATCHDOG.schedule(started::destroyForcibly, timeoutSeconds, TimeUnit.SECONDS);
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Result(-1, out);
      }
      // Killed by the watchdog: the exit code is the signal, and the output is whatever it had
      // managed to write. Reported as a failure rather than as a short answer.
      return new Result(watchdog.cancel(false) ? process.exitValue() : -1, out);
    } catch (IOException e) {
      return new Result(-1, "");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      return new Result(-1, "");
    } finally {
      if (watchdog != null) {
        watchdog.cancel(false);
      }
    }
  }
}
