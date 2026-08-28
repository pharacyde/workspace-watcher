package be.kleisli.ww.web;

import be.kleisli.ww.generated.types.FileStatus;
import be.kleisli.ww.generated.types.FileVersions;
import be.kleisli.ww.generated.types.GitSnapshot;
import be.kleisli.ww.generated.types.ProcessNode;
import be.kleisli.ww.generated.types.ProcessSnapshot;
import be.kleisli.ww.generated.types.Source;
import be.kleisli.ww.git.GitService;
import be.kleisli.ww.proc.ProcessTreeService;
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
