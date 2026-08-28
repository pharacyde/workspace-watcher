package be.kleisli.ww.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Fan-out hub between the collectors and the browser, with a bounded replay buffer so a dashboard
 * opened halfway through a session still shows what already happened.
 */
@Service
public class EventBus {

  private static final Logger log = LoggerFactory.getLogger(EventBus.class);

  /**
   * Per-subscriber buffer for a browser that cannot keep up.
   *
   * <p>Large enough that an ordinary burst is absorbed, small enough that a tab left paused during
   * a long build cannot grow the heap without bound.
   */
  private static final int SUBSCRIBER_BUFFER = 4096;

  private final AtomicLong seq = new AtomicLong();
  private final Deque<WatchEvent> history = new ArrayDeque<>();
  private final List<Consumer<WatchEvent>> subscribers = new CopyOnWriteArrayList<>();
  private final int historySize;

  public EventBus(WatcherProperties props) {
    this.historySize = props.getHistorySize();
  }

  public WatchEvent publish(WatchEvent.Builder builder) {
    WatchEvent event;
    // The number is taken under the same lock that appends, not before it. Taking it outside lets
    // two publishers swap order between numbering and appending: a subscriber registering in that
    // gap snapshots history ending at the higher number, and the lower one is then filtered out by
    // the sequence check that is supposed to remove duplicates. That is the silent hole in the feed
    // the design forbids, and it stopped being a narrow race the moment the collectors got more
    // than one thread to run on.
    synchronized (history) {
      event = builder.build(seq.incrementAndGet());
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

  /**
   * Empties the buffer.
   *
   * <p>The live feed is the chronicle of one workspace, so switching to another has to start a new
   * one; leaving the previous workspace's events in place would attribute them to the new one by
   * proximity alone. Recorded history keeps them, tagged with where they belonged.
   */
  public void clear() {
    synchronized (history) {
      history.clear();
    }
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
   * <p>The snapshot and the subscription are taken under the same lock {@link #publish} holds while
   * appending, so no event can slip through in between. An event may therefore be seen twice, which
   * the sequence filter removes — a duplicate is cheap, a gap is not.
   *
   * <p>The buffer is bounded and drops the <em>oldest</em> events under pressure. A subscriber that
   * stops consuming — a paused browser tab, a laptop asleep mid-build — would otherwise queue every
   * event produced until the heap gives out. Dropping the oldest keeps the feed showing what is
   * happening now, which is what a live view is for, and the loss is logged rather than hidden.
   *
   * <p>{@code onBackpressureBuffer} requests without limit from upstream, so the unbounded queue
   * inside {@code Flux.create} stays empty and this bound is the one that actually applies.
   */
  public Flux<WatchEvent> stream() {
    return Flux.<WatchEvent>create(
            sink -> {
              List<WatchEvent> backlog;
              Runnable unsubscribe;
              synchronized (history) {
                backlog = new ArrayList<>(history);
                long lastReplayed = backlog.isEmpty() ? 0 : backlog.get(backlog.size() - 1).seq();
                unsubscribe =
                    subscribe(
                        event -> {
                          if (event.seq() > lastReplayed) {
                            sink.next(event);
                          }
                        });
              }
              backlog.forEach(sink::next);
              sink.onCancel(unsubscribe::run);
              sink.onDispose(unsubscribe::run);
            },
            FluxSink.OverflowStrategy.BUFFER)
        .onBackpressureBuffer(
            SUBSCRIBER_BUFFER,
            dropped -> log.warn("subscriber too slow; dropped event seq={}", dropped.seq()),
            BufferOverflowStrategy.DROP_OLDEST);
  }

  public Runnable subscribe(Consumer<WatchEvent> subscriber) {
    subscribers.add(subscriber);
    return () -> subscribers.remove(subscriber);
  }
}
