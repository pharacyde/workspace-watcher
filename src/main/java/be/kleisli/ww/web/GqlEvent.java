package be.kleisli.ww.web;

import be.kleisli.ww.core.WatchEvent;
import tools.jackson.databind.ObjectMapper;

/**
 * GraphQL projection of a {@link WatchEvent}.
 *
 * <p>{@code detail} is carried as a JSON string rather than a custom scalar. Its shape genuinely
 * varies per source, so typing it would either lie or drag in an extra scalar library for a field
 * the UI treats as opaque anyway.
 */
public record GqlEvent(
        String seq,
        String ts,
        WatchEvent.Source source,
        String type,
        String summary,
        String path,
        String pid,
        String agent,
        String sessionId,
        String detail) {

    public static GqlEvent from(WatchEvent event, ObjectMapper mapper) {
        String detail = null;
        if (event.detail() != null) {
            try {
                detail = mapper.writeValueAsString(event.detail());
            } catch (RuntimeException e) {
                detail = null;
            }
        }
        return new GqlEvent(
                Long.toString(event.seq()),
                event.ts().toString(),
                event.source(),
                event.type(),
                event.summary(),
                event.path(),
                event.pid() == null ? null : Long.toString(event.pid()),
                event.agent(),
                event.sessionId(),
                detail);
    }
}
