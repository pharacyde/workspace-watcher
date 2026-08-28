package be.kleisli.ww.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables live here so the app can be pointed at any workspace without a rebuild. */
@ConfigurationProperties(prefix = "watcher")
public class WatcherProperties {

  /**
   * Workspace to observe.
   *
   * <p>Empty by default, which means "discover it". The watcher then waits for an agent hook to
   * tell it which project is actually being worked in, and adopts the most recently active one. Set
   * this to pin it to a single workspace instead.
   */
  private String workspace = "";

  /** Root of the Claude Code transcript store. */
  private String claudeHome = System.getProperty("user.home") + "/.claude";

  /** Directory names never descended into by the file watcher. */
  private List<String> ignoreDirs =
      List.of(
          ".git",
          "node_modules",
          "target",
          "build",
          "dist",
          "out",
          ".idea",
          ".vscode",
          ".gradle",
          ".mvn",
          "venv",
          ".venv",
          "__pycache__",
          ".next",
          ".cache",
          ".DS_Store");

  /** How often the workspace tree is rescanned for changes. */
  private long fsPollMs = 750;

  /** Files larger than this are still tracked, but never diffed inline. */
  private long maxDiffBytes = 512 * 1024;

  /** How often the transcript files are polled for appended lines. */
  private long transcriptPollMs = 500;

  /**
   * Directory hooks spool their payloads into. Must match WORKSPACE_WATCHER_SPOOL in the hook
   * script. Kept outside the workspace so spooled events never show up as file activity.
   */
  private String spool = System.getProperty("user.home") + "/.claude/workspace-watcher-spool";

  /**
   * How many agent sessions are offered for a workspace.
   *
   * <p>A long-running project accumulates hundreds - a real one here has 333 - and a list that long
   * is not something anyone picks from. The most recent are the ones worth following.
   */
  private int maxSessions = 25;

  /** How often the register of known workspaces is rescanned. */
  private long registryPollMs = 2000;

  /** Where recorded history lives. Set to an empty value to run without persistence. */
  private String database =
      System.getProperty("user.home") + "/.claude/workspace-watcher/events.db";

  /**
   * How the account behind these sessions is billed: {@code auto}, {@code api} or {@code
   * subscription}.
   *
   * <p>It decides what a cost figure is allowed to claim. On a subscription nobody pays per token,
   * so a dollar amount is what the tokens would have cost at API rates - a useful measure of how
   * heavy a session was, and not a bill. Presenting it as one would be a lie the tool tells
   * confidently.
   */
  private String billing = "auto";

  /**
   * PKCS12 keystore to serve HTTPS from. HTTP is used when the file is absent.
   *
   * <p>Switched on by the file existing rather than by a flag, so generating a certificate is the
   * whole act of enabling it and there is no second step to forget.
   */
  private String keystore =
      System.getProperty("user.home") + "/.claude/workspace-watcher/keystore.p12";

  /** Password for that keystore. A local development certificate; it protects nothing remote. */
  private String keystorePassword = "workspace-watcher";

  /** How long recorded history is kept. */
  private int retentionDays = 30;

  /**
   * Hard cap on recorded events, whatever the retention window says.
   *
   * <p>A row costs roughly 490 bytes measured, so a million rows is about half a gigabyte. That is
   * years of ordinary use, and it bounds the file even if a month turns out to mean far more events
   * than anyone expected.
   */
  private int maxStoredEvents = 1_000_000;

  /**
   * Resource samples kept, on top of the age limit.
   *
   * <p>Lower than the event cap because a sample is written on a schedule rather than when
   * something happens, so the count says nothing about how much went on. Two hundred thousand is
   * roughly a week at the current rate, which is longer than anyone scrolls a CPU chart back.
   */
  private int maxStoredMetrics = 200_000;

  /** How often the spool directory is drained. Cheap: the directory is normally empty. */
  private long spoolPollMs = 200;

  /** How often the process tree is rebuilt. Set to 0 to disable process polling. */
  private long processPollMs = 2000;

  /** Number of events kept for replay when a browser connects. */
  private int historySize = 2000;

  /**
   * Most individual file events one scan may report before it is collapsed into a summary.
   *
   * <p>A checkout, a branch switch or a build writing into a directory that is not ignored can
   * change thousands of files at once. Listing them one by one buries everything the agent did and
   * evicts real history from the replay buffer, for no information a reader can use.
   */
  private int maxFileEventsPerScan = 200;

  /** Root of the spool. Each workspace gets a subdirectory named after its escaped path. */
  public Path spoolBasePath() {
    return Paths.get(spool).toAbsolutePath().normalize();
  }

  public Path claudeProjectsPath() {
    return Paths.get(claudeHome).toAbsolutePath().normalize().resolve("projects");
  }

  public String getWorkspace() {
    return workspace;
  }

  public void setWorkspace(String workspace) {
    this.workspace = workspace;
  }

  public String getClaudeHome() {
    return claudeHome;
  }

  public void setClaudeHome(String claudeHome) {
    this.claudeHome = claudeHome;
  }

  public List<String> getIgnoreDirs() {
    return ignoreDirs;
  }

  public void setIgnoreDirs(List<String> ignoreDirs) {
    this.ignoreDirs = ignoreDirs;
  }

  public long getFsPollMs() {
    return fsPollMs;
  }

  public void setFsPollMs(long fsPollMs) {
    this.fsPollMs = fsPollMs;
  }

  public long getMaxDiffBytes() {
    return maxDiffBytes;
  }

  public void setMaxDiffBytes(long maxDiffBytes) {
    this.maxDiffBytes = maxDiffBytes;
  }

  public long getTranscriptPollMs() {
    return transcriptPollMs;
  }

  public void setTranscriptPollMs(long transcriptPollMs) {
    this.transcriptPollMs = transcriptPollMs;
  }

  public String getSpool() {
    return spool;
  }

  public void setSpool(String spool) {
    this.spool = spool;
  }

  public String getDatabase() {
    return database;
  }

  public void setDatabase(String database) {
    this.database = database;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  public String getBilling() {
    return billing;
  }

  public void setBilling(String billing) {
    this.billing = billing;
  }

  public String getKeystore() {
    return keystore;
  }

  public void setKeystore(String keystore) {
    this.keystore = keystore;
  }

  public String getKeystorePassword() {
    return keystorePassword;
  }

  public void setKeystorePassword(String keystorePassword) {
    this.keystorePassword = keystorePassword;
  }

  public int getMaxSessions() {
    return maxSessions;
  }

  public void setMaxSessions(int maxSessions) {
    this.maxSessions = maxSessions;
  }

  public int getMaxStoredMetrics() {
    return maxStoredMetrics;
  }

  public void setMaxStoredMetrics(int maxStoredMetrics) {
    this.maxStoredMetrics = maxStoredMetrics;
  }

  public int getMaxStoredEvents() {
    return maxStoredEvents;
  }

  public void setMaxStoredEvents(int maxStoredEvents) {
    this.maxStoredEvents = maxStoredEvents;
  }

  public long getRegistryPollMs() {
    return registryPollMs;
  }

  public void setRegistryPollMs(long registryPollMs) {
    this.registryPollMs = registryPollMs;
  }

  public long getSpoolPollMs() {
    return spoolPollMs;
  }

  public void setSpoolPollMs(long spoolPollMs) {
    this.spoolPollMs = spoolPollMs;
  }

  public long getProcessPollMs() {
    return processPollMs;
  }

  public void setProcessPollMs(long processPollMs) {
    this.processPollMs = processPollMs;
  }

  public int getMaxFileEventsPerScan() {
    return maxFileEventsPerScan;
  }

  public void setMaxFileEventsPerScan(int maxFileEventsPerScan) {
    this.maxFileEventsPerScan = maxFileEventsPerScan;
  }

  public int getHistorySize() {
    return historySize;
  }

  public void setHistorySize(int historySize) {
    this.historySize = historySize;
  }
}
