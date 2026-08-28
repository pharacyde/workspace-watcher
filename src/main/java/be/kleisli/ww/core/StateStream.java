package be.kleisli.ww.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * A broadcast of current state, as opposed to a chronicle of things that happened.
 *
 * <p>Keeping these apart is the difference between a readable dashboard and a firehose. The git
 * working tree and the process list are <em>state of the world</em>: only the latest value matters,
 * and republishing it does not belong in a chronological feed. Measured before this split, process
 * snapshots were 91% of all events — they filled the replay buffer and pushed real history out of
 * it, while saying nothing a reader could act on.
 *
 * <p>{@code replay().latest()} means a subscriber gets the current value the moment it connects, so
 * a panel is never briefly blank waiting for the next change.
 */
public class StateStream<T> {

  private final Sinks.Many<T> sink = Sinks.many().replay().latest();
  private volatile T current;

  public void publish(T value) {
    this.current = value;
    sink.tryEmitNext(value);
  }

  public T current() {
    return current;
  }

  public Flux<T> flux() {
    return sink.asFlux();
  }
}
