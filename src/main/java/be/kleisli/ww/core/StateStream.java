package be.kleisli.ww.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(StateStream.class);

  private final Sinks.Many<T> sink = Sinks.many().replay().latest();
  private volatile T current;

  /**
   * Publishes a new value.
   *
   * <p>A failed emit is logged rather than swallowed. Producers only publish on change, so a
   * dropped emit is not retried: the next poll sees "nothing changed" and returns early, and the
   * panel silently freezes on a stale value until the state happens to move again.
   */
  public void publish(T value) {
    this.current = value;
    Sinks.EmitResult result = sink.tryEmitNext(value);
    if (result.isFailure()) {
      log.warn("state emit failed ({}); a panel may now be showing a stale value", result);
    }
  }

  public T current() {
    return current;
  }

  public Flux<T> flux() {
    return sink.asFlux();
  }

  /**
   * How many panels are currently listening.
   *
   * <p>A collector that costs real CPU can use this to slow down when nobody is looking. State is
   * only interesting while something displays it - unlike the chronicle, where a missed event is
   * gone for good.
   */
  public int subscribers() {
    return sink.currentSubscriberCount();
  }
}
