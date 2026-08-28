package be.kleisli.ww.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;

import be.kleisli.ww.claude.HookEvents;
import be.kleisli.ww.claude.TranscriptTailService;
import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;
import be.kleisli.ww.core.Text;
import be.kleisli.ww.core.WatcherProperties;
import be.kleisli.ww.git.GitService;
import be.kleisli.ww.proc.ProcessTreeService;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/** The entire API. There is no REST surface; hooks post the {@code recordAgentEvent} mutation. */
@Controller
public class WatchGraphQlController {

    public record Status(String workspace, boolean workspaceExists, String os,
                         List<String> transcriptDirs, GitService.Snapshot git,
                         List<ProcessTreeService.Node> processes) {}

    public record Diff(String path, String staged, String unstaged) {}

    private final WatcherProperties props;
    private final EventBus bus;
    private final GitService git;
    private final ProcessTreeService processes;
    private final TranscriptTailService transcripts;
    private final ObjectMapper mapper = new ObjectMapper();

    public WatchGraphQlController(WatcherProperties props, EventBus bus, GitService git,
                                  ProcessTreeService processes, TranscriptTailService transcripts) {
        this.props = props;
        this.bus = bus;
        this.git = git;
        this.processes = processes;
        this.transcripts = transcripts;
    }

    @QueryMapping
    public Status status() {
        return new Status(
                props.workspacePath().toString(),
                Files.isDirectory(props.workspacePath()),
                System.getProperty("os.name"),
                transcripts.watchedTranscripts(),
                git.current(),
                processes.current());
    }

    /** Path traversal is rejected: only paths resolving back inside the workspace are served. */
    @QueryMapping
    public Diff diff(@Argument String path) {
        Path workspace = props.workspacePath();
        Path resolved = workspace.resolve(path).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("path outside workspace");
        }
        Map<String, String> result = git.diff(workspace.relativize(resolved).toString());
        return new Diff(result.get("path"), result.get("staged"), result.get("unstaged"));
    }

    @QueryMapping
    public List<GqlEvent> recentEvents(@Argument Integer limit) {
        return bus.recent(limit == null ? 200 : limit).stream()
                .map(event -> GqlEvent.from(event, mapper))
                .toList();
    }

    @SubscriptionMapping
    public Flux<GqlEvent> events() {
        return bus.stream().map(event -> GqlEvent.from(event, mapper));
    }

    /**
     * Remote delivery path for agent hooks.
     *
     * <p>Locally, hooks spool to a directory instead — cheaper, and events survive the watcher
     * being down. This mutation exists for a watcher running on a different host than the agent.
     *
     * <p>Base64 in, so the shell side needs no quoting gymnastics and no jq. Always returns true:
     * a hook blocks the agent until it answers, so this must never fail or stall on bad input.
     */
    @MutationMapping
    public boolean recordAgentEvent(@Argument String payloadBase64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            HookEvents.publish(bus, mapper, decoded, "graphql");
        } catch (RuntimeException e) {
            bus.publish(WatchEvent.of(WatchEvent.Source.HOOK, "HOOK")
                    .summary("undecodable hook payload")
                    .detail("error", e.toString()));
        }
        return true;
    }
}
