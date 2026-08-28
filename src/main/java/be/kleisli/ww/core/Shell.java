package be.kleisli.ww.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal, timeout-bounded process runner. Never uses a shell, so nothing gets interpreted. */
public final class Shell {

    public record Result(int exitCode, String stdout) {
        public boolean ok() { return exitCode == 0; }
        public List<String> lines() {
            return stdout.isEmpty() ? List.of() : List.of(stdout.split("\n", -1));
        }
    }

    private Shell() {}

    public static Result run(Path workingDir, List<String> command, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = null;
        try {
            process = pb.start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, out);
            }
            return new Result(process.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(-1, "");
        }
    }
}
