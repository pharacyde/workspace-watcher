package be.kleisli.ww.web;

import be.kleisli.ww.claude.HookEvents;
import be.kleisli.ww.claude.TranscriptTailService;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.generated.types.Diff;
import be.kleisli.ww.generated.types.FileVersions;
import be.kleisli.ww.generated.types.GitSnapshot;
import be.kleisli.ww.generated.types.ProcessSnapshot;
import be.kleisli.ww.generated.types.Status;
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
  private final EventBus eventBus;
  private final GitService git;
  private final ProcessTreeService processes;
  private final TranscriptTailService transcripts;
  private final ApiMapper mapper;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public WatchDataFetcher(
      WatcherProperties properties,
      EventBus eventBus,
      GitService git,
      ProcessTreeService processes,
      TranscriptTailService transcripts,
      ApiMapper mapper) {
    this.properties = properties;
    this.eventBus = eventBus;
    this.git = git;
    this.processes = processes;
    this.transcripts = transcripts;
    this.mapper = mapper;
  }

  @DgsQuery
  public Status status() {
    return Status.newBuilder()
        .workspace(properties.workspacePath().toString())
        .workspaceExists(Files.isDirectory(properties.workspacePath()))
        .os(System.getProperty("os.name"))
        .transcriptDirs(transcripts.watchedTranscripts())
        .git(mapper.toGitSnapshot(git.current()))
        .processes(mapper.toProcessSnapshot(processes.currentSnapshot()))
        .build();
  }

  @DgsQuery
  public Diff diff(@InputArgument String path) {
    return mapper.toDiff(git.diff(insideWorkspace(path)));
  }

  /**
   * Rejects path traversal: only paths that resolve back inside the workspace are served.
   *
   * @return the path relative to the workspace root
   */
  private String insideWorkspace(String path) {
    Path workspace = properties.workspacePath();
    Path resolved = workspace.resolve(path).normalize();
    if (!resolved.startsWith(workspace)) {
      throw new IllegalArgumentException("path outside workspace");
    }
    return workspace.relativize(resolved).toString();
  }

  /** Both sides of a file, for a side-by-side editor. */
  @DgsQuery
  public FileVersions fileVersions(@InputArgument String path) {
    return mapper.toFileVersions(git.versions(insideWorkspace(path)));
  }

  @DgsQuery
  public List<be.kleisli.ww.generated.types.WatchEvent> recentEvents(@InputArgument Integer limit) {
    return eventBus.recent(limit == null ? DEFAULT_EVENT_LIMIT : limit).stream()
        .map(mapper::toEvent)
        .toList();
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
