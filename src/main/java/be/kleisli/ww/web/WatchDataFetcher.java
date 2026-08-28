package be.kleisli.ww.web;

import be.kleisli.ww.claude.HookEvents;
import be.kleisli.ww.claude.SessionRegistry;
import be.kleisli.ww.claude.TranscriptTailService;
import be.kleisli.ww.claude.WorkspaceRegistry;
import be.kleisli.ww.core.ActiveWorkspace;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.generated.types.FileVersions;
import be.kleisli.ww.generated.types.GitSnapshot;
import be.kleisli.ww.generated.types.ProcessSnapshot;
import be.kleisli.ww.generated.types.SessionEntry;
import be.kleisli.ww.generated.types.Status;
import be.kleisli.ww.generated.types.WorkspaceEntry;
import be.kleisli.ww.git.GitService;
import be.kleisli.ww.proc.ProcessTreeService;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.reactivestreams.Publisher;
import tools.jackson.databind.ObjectMapper;

/**
 * The entire API. There is no REST surface.
 *
 * <p>Written in the DGS programming model rather than Spring's {@code @QueryMapping}. Netflix's own
 * guidance is explicit that the two styles should not be mixed in one codebase, because some
 * features do not work across both.
 */
@DgsComponent
public class WatchDataFetcher {

  private static final int DEFAULT_EVENT_LIMIT = 200;

  private final WatcherProperties properties;
  private final ActiveWorkspace active;
  private final WorkspaceRegistry registry;
  private final SessionRegistry sessions;
  private final EventBus eventBus;
  private final GitService git;
  private final ProcessTreeService processes;
  private final TranscriptTailService transcripts;
  private final ApiMapper mapper;
  private final ObjectMapper objectMapper;

  public WatchDataFetcher(
      WatcherProperties properties,
      ActiveWorkspace active,
      WorkspaceRegistry registry,
      SessionRegistry sessions,
      EventBus eventBus,
      GitService git,
      ProcessTreeService processes,
      TranscriptTailService transcripts,
      ApiMapper mapper,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.active = active;
    this.registry = registry;
    this.sessions = sessions;
    this.eventBus = eventBus;
    this.git = git;
    this.processes = processes;
    this.transcripts = transcripts;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @DgsQuery
  public Status status() {
    Path workspace = active.get();
    return Status.newBuilder()
        .workspace(workspace == null ? null : workspace.toString())
        .workspaceExists(workspace != null && Files.isDirectory(workspace))
        .os(System.getProperty("os.name"))
        .transcriptDirs(transcripts.watchedTranscripts())
        .git(mapper.toGitSnapshot(git.current()))
        .processes(mapper.toProcessSnapshot(processes.currentSnapshot()))
        .build();
  }

  /**
   * Both sides of a file, for a side-by-side editor.
   *
   * <p>The path is whatever git reported, so it is repository-root-relative. Resolving and
   * validating it belongs to {@link GitService}, because only it knows where the root is.
   */
  @DgsQuery
  public FileVersions fileVersions(@InputArgument String path) {
    git.resolveInRepo(path);
    return mapper.toFileVersions(git.versions(path));
  }

  @DgsQuery
  public List<be.kleisli.ww.generated.types.WatchEvent> recentEvents(@InputArgument Integer limit) {
    return eventBus.recent(limit == null ? DEFAULT_EVENT_LIMIT : limit).stream()
        .map(mapper::toEvent)
        .toList();
  }

  /** Every workspace that has registered itself, most recently active first. */
  @DgsQuery
  public List<WorkspaceEntry> workspaces() {
    return mapper.toWorkspaces(registry.current());
  }

  /** Agent sessions in the watched workspace, most recently active first. */
  @DgsQuery
  public List<SessionEntry> sessions() {
    return mapper.toSessions(sessions.current());
  }

  /** The chronicle: things that happened, in order. State does not belong here. */
  @DgsSubscription
  public Publisher<be.kleisli.ww.generated.types.WatchEvent> events() {
    return eventBus.stream().map(mapper::toEvent);
  }

  /** Current working tree. Emits the present value on subscribe, then on every change. */
  @DgsSubscription
  public Publisher<GitSnapshot> gitStatus() {
    return git.stream().flux().map(mapper::toGitSnapshot);
  }

  /** Current process tree. Emits the present value on subscribe, then on every change. */
  @DgsSubscription
  public Publisher<ProcessSnapshot> processTree() {
    return processes.stream().flux().map(mapper::toProcessSnapshot);
  }

  /** The register of known workspaces. */
  @DgsSubscription(field = "workspaces")
  public Publisher<List<WorkspaceEntry>> workspacesSubscription() {
    return registry.stream().flux().map(mapper::toWorkspaces);
  }

  /** Agent sessions in the watched workspace. */
  @DgsSubscription(field = "sessions")
  public Publisher<List<SessionEntry>> sessionsSubscription() {
    return sessions.stream().flux().map(mapper::toSessions);
  }

  /** The workspace being watched. */
  @DgsSubscription
  public Publisher<String> activeWorkspace() {
    return active.stream().flux();
  }

  /**
   * Points the watcher at a workspace.
   *
   * <p>Collectors read the active workspace on every poll rather than caching it, so this takes
   * effect within one interval and nothing restarts.
   */
  @DgsMutation
  public boolean watchWorkspace(@InputArgument String path) {
    active.set(Path.of(path));
    return true;
  }

  /**
   * Remote delivery path for agent hooks.
   *
   * <p>Locally, hooks spool to a directory instead - cheaper, and events survive the watcher being
   * down. This mutation exists for a watcher running on a different host than the agent.
   *
   * <p>Base64 in, so the shell side needs no quoting gymnastics and no jq. Always returns true: a
   * hook blocks the agent until it answers, so this must never fail or stall on bad input.
   */
  @DgsMutation
  public boolean recordAgentEvent(@InputArgument String payloadBase64) {
    try {
      String decoded =
          new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
      HookEvents.publish(eventBus, objectMapper, decoded, "graphql");
    } catch (RuntimeException e) {
      eventBus.publish(
          WatchEvent.of(WatchEvent.Source.HOOK, "HOOK")
              .summary("undecodable hook payload")
              .detail("error", e.toString()));
    }
    return true;
  }
}
