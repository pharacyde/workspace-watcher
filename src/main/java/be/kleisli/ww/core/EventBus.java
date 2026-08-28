package be.kleisli.ww.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Fan-out hub between the collectors and the browser, with a bounded replay buffer so a
 * dashboard opened halfway through a session still shows what already happened.
 */
@Service
public class EventBus {

    private final AtomicLong seq = new AtomicLong();
    private final Deque<WatchEvent> history = new ArrayDeque<>();
    private final List<Consumer<WatchEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final int historySize;

    public EventBus(WatcherProperties props) {
        this.historySize = props.getHistorySize();
    }

    public WatchEvent publish(WatchEvent.Builder builder) {
        WatchEvent event = builder.build(seq.incrementAndGet());
        synchronized (history) {
            history.addLast(event);
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
        for (Consumer<WatchEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
                // A dead browser connection must never take down a collector.
            }
        }
        return event;
    }

    public List<WatchEvent> replay() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** The most recent {@code limit} events, oldest first. */
    public List<WatchEvent> recent(int limit) {
        List<WatchEvent> all = replay();
        int from = Math.max(0, all.size() - Math.max(0, limit));
        return all.subList(from, all.size());
    }

    /**
     * A stream that starts with the buffered history and continues live.
     *
     * <p>The snapshot and the subscription are taken under the same lock {@link #publish} holds
     * while appending, so no event can slip through in between. An event may therefore be seen
     * twice, which the sequence filter removes — a duplicate is cheap, a gap is not.
     */
    public Flux<WatchEvent> stream() {
        return Flux.create(sink -> {
            List<WatchEvent> backlog;
            Runnable unsubscribe;
            synchronized (history) {
                backlog = new ArrayList<>(history);
                long lastReplayed = backlog.isEmpty() ? 0 : backlog.get(backlog.size() - 1).seq();
                unsubscribe = subscribe(event -> {
                    if (event.seq() > lastReplayed) {
                        sink.next(event);
                    }
                });
            }
            backlog.forEach(sink::next);
            sink.onCancel(unsubscribe::run);
            sink.onDispose(unsubscribe::run);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public Runnable subscribe(Consumer<WatchEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
