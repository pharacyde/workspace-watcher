package be.kleisli.ww.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables live here so the app can be pointed at any workspace without a rebuild. */
@ConfigurationProperties(prefix = "watcher")
public class WatcherProperties {

    /** Workspace to observe. Defaults to the directory the app was started from. */
    private String workspace = System.getProperty("user.dir");

    /** Root of the Claude Code transcript store. */
    private String claudeHome = System.getProperty("user.home") + "/.claude";

    /** Directory names never descended into by the file watcher. */
    private List<String> ignoreDirs = List.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".idea", ".vscode", ".gradle", ".mvn", "venv", ".venv",
            "__pycache__", ".next", ".cache", ".DS_Store");

    /** How often the workspace tree is rescanned for changes. */
    private long fsPollMs = 750;

    /** Files larger than this are still tracked, but never diffed inline. */
    private long maxDiffBytes = 512 * 1024;

    /** How often the transcript files are polled for appended lines. */
    private long transcriptPollMs = 500;

    /** How often the process tree is rebuilt. Set to 0 to disable process polling. */
    private long processPollMs = 2000;

    /** Number of events kept for replay when a browser connects. */
    private int historySize = 2000;

    public Path workspacePath() {
        return Paths.get(workspace).toAbsolutePath().normalize();
    }

    public Path claudeProjectsPath() {
        return Paths.get(claudeHome).toAbsolutePath().normalize().resolve("projects");
    }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public String getClaudeHome() { return claudeHome; }
    public void setClaudeHome(String claudeHome) { this.claudeHome = claudeHome; }
    public List<String> getIgnoreDirs() { return ignoreDirs; }
    public void setIgnoreDirs(List<String> ignoreDirs) { this.ignoreDirs = ignoreDirs; }
    public long getFsPollMs() { return fsPollMs; }
    public void setFsPollMs(long fsPollMs) { this.fsPollMs = fsPollMs; }
    public long getMaxDiffBytes() { return maxDiffBytes; }
    public void setMaxDiffBytes(long maxDiffBytes) { this.maxDiffBytes = maxDiffBytes; }
    public long getTranscriptPollMs() { return transcriptPollMs; }
    public void setTranscriptPollMs(long transcriptPollMs) { this.transcriptPollMs = transcriptPollMs; }
    public long getProcessPollMs() { return processPollMs; }
    public void setProcessPollMs(long processPollMs) { this.processPollMs = processPollMs; }
    public int getHistorySize() { return historySize; }
    public void setHistorySize(int historySize) { this.historySize = historySize; }
}
