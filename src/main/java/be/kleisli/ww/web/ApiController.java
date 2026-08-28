package be.kleisli.ww.web;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import be.kleisli.ww.claude.TranscriptTailService;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.git.GitService;
import be.kleisli.ww.proc.ProcessTreeService;

@RestController
public class ApiController {

    private final WatcherProperties props;
    private final GitService git;
    private final ProcessTreeService processes;
    private final TranscriptTailService transcripts;

    public ApiController(WatcherProperties props, GitService git, ProcessTreeService processes,
                         TranscriptTailService transcripts) {
        this.props = props;
        this.git = git;
        this.processes = processes;
        this.transcripts = transcripts;
    }

    /** Everything the UI needs to tell the user honestly what is and is not being observed. */
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("workspace", props.workspacePath().toString());
        status.put("workspaceExists", Files.isDirectory(props.workspacePath()));
        status.put("transcriptDirs", transcripts.watchedTranscripts());
        status.put("git", git.current());
        status.put("processes", processes.current());
        status.put("os", System.getProperty("os.name"));
        return status;
    }

    /**
     * Unified diff for one workspace-relative path.
     *
     * <p>Path traversal is rejected: only paths that resolve back inside the workspace are served.
     */
    @GetMapping("/api/diff")
    public ResponseEntity<?> diff(@RequestParam("path") String path) {
        var workspace = props.workspacePath();
        var resolved = workspace.resolve(path).normalize();
        if (!resolved.startsWith(workspace)) {
            return ResponseEntity.badRequest().body(Map.of("error", "path outside workspace"));
        }
        return ResponseEntity.ok(git.diff(workspace.relativize(resolved).toString()));
    }
}
