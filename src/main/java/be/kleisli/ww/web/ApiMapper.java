package be.kleisli.ww.web;

import be.kleisli.ww.claude.SessionRegistry;
import be.kleisli.ww.claude.WorkspaceRegistry;
import be.kleisli.ww.generated.types.ActivityBucket;
import be.kleisli.ww.generated.types.FileStatus;
import be.kleisli.ww.generated.types.FileVersions;
import be.kleisli.ww.generated.types.GitSnapshot;
import be.kleisli.ww.generated.types.GuardAction;
import be.kleisli.ww.generated.types.GuardConfig;
import be.kleisli.ww.generated.types.GuardDecision;
import be.kleisli.ww.generated.types.GuardRule;
import be.kleisli.ww.generated.types.GuardRuleKind;
import be.kleisli.ww.generated.types.ModelUsage;
import be.kleisli.ww.generated.types.ProcessNode;
import be.kleisli.ww.generated.types.ProcessSnapshot;
import be.kleisli.ww.generated.types.SessionEntry;
import be.kleisli.ww.generated.types.Source;
import be.kleisli.ww.generated.types.UsageSummary;
import be.kleisli.ww.generated.types.WorkspaceEntry;
import be.kleisli.ww.git.GitService;
import be.kleisli.ww.guard.GuardService;
import be.kleisli.ww.proc.ProcessTreeService;
import be.kleisli.ww.store.EventStore;
import be.kleisli.ww.usage.UsageService;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps domain types onto the generated wire types.
 *
 * <p>This layer exists so the schema can be the contract rather than a description. The types in
 * {@code be.kleisli.ww.generated} are generated from {@code schema.graphqls} at build time, so a
 * field renamed in the schema breaks compilation here instead of silently returning null to a
 * client. It also keeps the domain records free to change shape without that leaking to the API.
 */
@Component
public class ApiMapper {

  private final ObjectMapper objectMapper;

  public ApiMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public GitSnapshot toGitSnapshot(GitService.Snapshot snapshot) {
    return GitSnapshot.newBuilder()
        .repo(snapshot.repo())
        .branch(snapshot.branch())
        .head(snapshot.head())
        .headSubject(snapshot.headSubject())
        .files(snapshot.files().stream().map(this::toFileStatus).toList())
        .build();
  }

  private FileStatus toFileStatus(GitService.FileStatus status) {
    return FileStatus.newBuilder()
        .path(status.path())
        .status(status.status())
        .staged(status.staged())
        .build();
  }

  public ProcessSnapshot toProcessSnapshot(ProcessTreeService.Snapshot snapshot) {
    return ProcessSnapshot.newBuilder()
        .at(snapshot.at())
        .total(snapshot.total())
        .roots(toNodes(snapshot.roots()))
        .build();
  }

  private List<ProcessNode> toNodes(List<ProcessTreeService.Node> nodes) {
    return nodes.stream()
        .map(
            node ->
                ProcessNode.newBuilder()
                    .pid(Long.toString(node.pid()))
                    .command(node.command())
                    .cwd(node.cwd())
                    .children(toNodes(node.children()))
                    .build())
        .toList();
  }

  public List<WorkspaceEntry> toWorkspaces(List<WorkspaceRegistry.Entry> entries) {
    return entries.stream()
        .map(
            entry ->
                WorkspaceEntry.newBuilder()
                    .path(entry.path())
                    .lastActivity(entry.lastActivity())
                    .pendingEvents(entry.pendingEvents())
                    .exists(entry.exists())
                    .build())
        .toList();
  }

  /**
   * Maps a recorded row.
   *
   * <p>{@code detail} is already the JSON string it was stored as, so it is passed through rather
   * than serialised again — encoding it twice would leave the client unwrapping a string.
   */
  public be.kleisli.ww.generated.types.WatchEvent toEvent(EventStore.Stored stored) {
    return be.kleisli.ww.generated.types.WatchEvent.newBuilder()
        .seq(stored.seq())
        .ts(stored.ts())
        .source(Source.valueOf(stored.source()))
        .type(stored.type())
        .summary(stored.summary())
        .path(stored.path())
        .agent(stored.agent())
        .sessionId(stored.sessionId())
        .detail(stored.detail())
        .build();
  }

  public List<ActivityBucket> toActivity(List<EventStore.Bucket> buckets) {
    return buckets.stream()
        .map(
            bucket ->
                ActivityBucket.newBuilder()
                    .index(bucket.index())
                    .from(bucket.from())
                    .count(bucket.count())
                    .agentCount(bucket.agentCount())
                    .build())
        .toList();
  }

  public UsageSummary toUsage(UsageService.Summary summary) {
    return UsageSummary.newBuilder()
        .models(
            summary.models().stream()
                .map(
                    m ->
                        ModelUsage.newBuilder()
                            .model(m.model())
                            .tokens(toTokens(m.tokens()))
                            .costUsd(m.costUsd())
                            .build())
                .toList())
        .tokens(toTokens(summary.tokens()))
        .costUsd(summary.costUsd())
        .build();
  }

  private be.kleisli.ww.generated.types.TokenUsage toTokens(be.kleisli.ww.usage.TokenUsage tokens) {
    // Float rather than Int on the wire: a long session reads hundreds of millions of cached
    // tokens, and totals across sessions run past what a 32-bit GraphQL Int can hold.
    return be.kleisli.ww.generated.types.TokenUsage.newBuilder()
        .input((double) tokens.input())
        .output((double) tokens.output())
        .cacheWrite5m((double) tokens.cacheWrite5m())
        .cacheWrite1h((double) tokens.cacheWrite1h())
        .cacheRead((double) tokens.cacheRead())
        .total((double) tokens.total())
        .build();
  }

  public GuardConfig toGuardConfig(GuardService.Config config) {
    return GuardConfig.newBuilder()
        .enabled(config.enabled())
        .denyOutsideWorkspace(config.denyOutsideWorkspace())
        .rules(
            config.rules().stream()
                .map(
                    rule ->
                        GuardRule.newBuilder()
                            .kind(GuardRuleKind.valueOf(rule.kind().name()))
                            .pattern(rule.pattern())
                            .action(GuardAction.valueOf(rule.action().name()))
                            .reason(rule.reason())
                            .build())
                .toList())
        .build();
  }

  public GuardDecision toGuardDecision(GuardService.Decision decision) {
    return GuardDecision.newBuilder()
        .action(GuardAction.valueOf(decision.action().name()))
        .reason(decision.reason())
        .rule(decision.rule())
        .build();
  }

  public List<SessionEntry> toSessions(List<SessionRegistry.Entry> entries) {
    return entries.stream()
        .map(
            entry ->
                SessionEntry.newBuilder()
                    .id(entry.id())
                    .title(entry.title())
                    .lastActivity(entry.lastActivity())
                    .live(entry.live())
                    .build())
        .toList();
  }

  public FileVersions toFileVersions(GitService.Versions versions) {
    return FileVersions.newBuilder()
        .path(versions.path())
        .head(versions.head())
        .working(versions.working())
        .binary(versions.binary())
        .tooLarge(versions.tooLarge())
        .build();
  }

  /**
   * {@code detail} is carried as a JSON string rather than a custom scalar. Its shape genuinely
   * varies per source, so typing it would either lie or drag in a scalar library for a field the UI
   * treats as opaque.
   */
  public be.kleisli.ww.generated.types.WatchEvent toEvent(be.kleisli.ww.core.WatchEvent event) {
    String detail = null;
    if (event.detail() != null) {
      try {
        detail = objectMapper.writeValueAsString(event.detail());
      } catch (RuntimeException e) {
        detail = null;
      }
    }
    return be.kleisli.ww.generated.types.WatchEvent.newBuilder()
        .seq(Long.toString(event.seq()))
        .ts(event.ts().toString())
        .source(Source.valueOf(event.source().name()))
        .type(event.type())
        .summary(event.summary())
        .path(event.path())
        .pid(event.pid() == null ? null : Long.toString(event.pid()))
        .agent(event.agent())
        .sessionId(event.sessionId())
        .detail(detail)
        .build();
  }
}
