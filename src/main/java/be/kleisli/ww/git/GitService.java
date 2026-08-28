package be.kleisli.ww.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.Shell;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;

/**
 * Git state for the workspace.
 *
 * <p>Shells out to {@code git} rather than embedding JGit: it is faster on large repositories,
 * it honours the user's own git config and hooks, and it cannot drift from what the developer
 * sees in their own terminal.
 */
@Service
public class GitService {

    public record FileStatus(String path, String status, boolean staged) {}
    public record Snapshot(boolean repo, String branch, String head, String headSubject, List<FileStatus> files) {}

    private final WatcherProperties props;
    private final EventBus bus;
    private volatile Snapshot last = new Snapshot(false, null, null, null, List.of());

    public GitService(WatcherProperties props, EventBus bus) {
        this.props = props;
        this.bus = bus;
    }

    public Snapshot current() {
        return last;
    }

    /** Recomputes status and publishes an event only when something actually changed. */
    public synchronized void refresh() {
        Snapshot snapshot = read();
        if (snapshot.equals(last)) {
            return;
        }
        last = snapshot;
        bus.publish(WatchEvent.of(WatchEvent.Source.GIT, "STATUS")
                .summary(snapshot.repo()
                        ? snapshot.files().size() + " changed file(s) on " + snapshot.branch()
                        : "not a git repository")
                .detail(snapshot));
    }

    private Snapshot read() {
        Path ws = props.workspacePath();
        if (!Files.isDirectory(ws)) {
            return new Snapshot(false, null, null, null, List.of());
        }
        Shell.Result inside = Shell.run(ws, List.of("git", "rev-parse", "--is-inside-work-tree"), 5);
        if (!inside.ok() || !inside.stdout().strip().equals("true")) {
            return new Snapshot(false, null, null, null, List.of());
        }

        String branch = Shell.run(ws, List.of("git", "branch", "--show-current"), 5).stdout().strip();
        String head = Shell.run(ws, List.of("git", "rev-parse", "--short", "HEAD"), 5).stdout().strip();
        String subject = Shell.run(ws, List.of("git", "log", "-1", "--format=%s"), 5).stdout().strip();

        List<FileStatus> files = new ArrayList<>();
        Shell.Result status = Shell.run(ws, List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), 15);
        for (String line : status.lines()) {
            if (line.length() < 4) {
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String path = line.substring(3);
            // Renames are reported as "old -> new"; the new path is the one worth showing.
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0) {
                path = path.substring(arrow + 4);
            }
            files.add(new FileStatus(path, describe(index, worktree), index != ' ' && index != '?'));
        }
        return new Snapshot(true, branch.isEmpty() ? "(detached)" : branch, head, subject, files);
    }

    private static String describe(char index, char worktree) {
        if (index == '?' ) return "untracked";
        if (index == 'A' || worktree == 'A') return "added";
        if (index == 'D' || worktree == 'D') return "deleted";
        if (index == 'R') return "renamed";
        return "modified";
    }

    /** Unified diff for one path, staged and unstaged combined. Empty for untracked files. */
    public Map<String, String> diff(String relativePath) {
        Path ws = props.workspacePath();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("path", relativePath);
        result.put("unstaged", Shell.run(ws, List.of("git", "diff", "--", relativePath), 15).stdout());
        result.put("staged", Shell.run(ws, List.of("git", "diff", "--cached", "--", relativePath), 15).stdout());
        if (result.get("unstaged").isBlank() && result.get("staged").isBlank()) {
            // Untracked: show the file as if every line were added.
            Shell.Result untracked = Shell.run(ws,
                    List.of("git", "diff", "--no-index", "--", "/dev/null", relativePath), 15);
            result.put("unstaged", untracked.stdout());
        }
        return result;
    }
}
