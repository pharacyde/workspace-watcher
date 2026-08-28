package be.kleisli.ww.web;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import be.kleisli.ww.core.EventBus;
import be.kleisli.ww.core.WatchEvent;

/**
 * Server-Sent Events rather than WebSockets: the stream is one-directional, SSE reconnects on its
 * own, and it survives ordinary HTTP proxies and SSH tunnels without extra configuration.
 */
@RestController
public class EventStreamController {

    private static final long NO_TIMEOUT = 0L;

    private final EventBus bus;

    public EventStreamController(EventBus bus) {
        this.bus = bus;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        AtomicBoolean closed = new AtomicBoolean(false);

        // Replay first so a dashboard opened mid-session is not blank.
        for (WatchEvent event : bus.replay()) {
            if (!send(emitter, event, closed)) {
                return emitter;
            }
        }

        Runnable unsubscribe = bus.subscribe(event -> {
            if (!send(emitter, event, closed)) {
                throw new IllegalStateException("emitter closed");
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        return emitter;
    }

    private boolean send(SseEmitter emitter, WatchEvent event, AtomicBoolean closed) {
        if (closed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name("watch").data(event));
            return true;
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            emitter.complete();
            return false;
        }
    }
}
