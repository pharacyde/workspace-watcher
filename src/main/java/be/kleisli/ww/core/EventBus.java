package be.kleisli.ww.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

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

    public Runnable subscribe(Consumer<WatchEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
