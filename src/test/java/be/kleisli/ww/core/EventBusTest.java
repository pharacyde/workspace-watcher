package be.kleisli.ww.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class EventBusTest {

  private static EventBus busWithHistory(int size) {
    WatcherProperties props = new WatcherProperties();
    props.setHistorySize(size);
    return new EventBus(props);
  }

  private static WatchEvent.Builder event(String summary) {
    return WatchEvent.of(WatchEvent.Source.FS, "CREATED").summary(summary);
  }

  @Test
  @DisplayName("numbers events from one upwards")
  void assignsSequenceNumbers() {
    EventBus bus = busWithHistory(10);
    assertThat(bus.publish(event("a")).seq()).isEqualTo(1);
    assertThat(bus.publish(event("b")).seq()).isEqualTo(2);
  }

  @Test
  @DisplayName("keeps only the newest events once the buffer is full")
  void boundsHistory() {
    EventBus bus = busWithHistory(3);
    for (int i = 0; i < 10; i++) {
      bus.publish(event("e" + i));
    }
    assertThat(bus.replay()).extracting(WatchEvent::summary).containsExactly("e7", "e8", "e9");
  }

  @Test
  @DisplayName("recent() returns the tail, oldest first")
  void returnsRecentTail() {
    EventBus bus = busWithHistory(10);
    for (int i = 0; i < 5; i++) {
      bus.publish(event("e" + i));
    }
    assertThat(bus.recent(2)).extracting(WatchEvent::summary).containsExactly("e3", "e4");
    assertThat(bus.recent(99)).hasSize(5);
  }

  @Test
  @DisplayName("replays history and then continues live, with no gap and no duplicate")
  void streamReplaysThenGoesLive() {
    EventBus bus = busWithHistory(10);
    bus.publish(event("before-1"));
    bus.publish(event("before-2"));

    List<String> seen = new CopyOnWriteArrayList<>();
    StepVerifier.create(bus.stream())
        .recordWith(ArrayList::new)
        .expectNextCount(2)
        .then(() -> bus.publish(event("after")))
        .assertNext(e -> seen.add(e.summary()))
        .thenCancel()
        .verify(java.time.Duration.ofSeconds(5));

    // The live event arrives exactly once: the overlap between the snapshot and the subscription
    // is removed by sequence number rather than left as a duplicate.
    assertThat(seen).containsExactly("after");
  }

  @Test
  @DisplayName("a subscriber that never reads cannot grow the buffer without bound")
  void boundsSlowSubscriber() {
    EventBus bus = busWithHistory(10);
    // Requesting nothing models a paused browser tab. Publishing far more than the per-subscriber
    // buffer must not throw or retain everything; the oldest are dropped instead.
    StepVerifier.create(bus.stream(), 0)
        .then(
            () -> {
              for (int i = 0; i < 10_000; i++) {
                bus.publish(event("flood-" + i));
              }
            })
        .thenRequest(1)
        .assertNext(e -> assertThat(e.summary()).startsWith("flood-"))
        .thenCancel()
        .verify(java.time.Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("numbers and appends under one lock, so a subscriber joining mid-flight sees no gap")
  void noGapWhenPublishersRace() throws Exception {
    // The collectors run on a scheduler pool, so several of them publish at the same time as a
    // matter of course. Numbering outside the lock lets two swap order between taking a number and
    // appending; a subscriber registering in that gap snapshots the higher number and the lower one
    // is then filtered out as a duplicate - a silent hole in the feed, which is the one thing the
    // subscription is not allowed to have.
    EventBus bus = busWithHistory(20_000);
    int threads = 8;
    int each = 500;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            go.await();
            for (int i = 0; i < each; i++) {
              bus.publish(event("e"));
            }
            return null;
          });
    }
    go.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    List<Long> seqs = bus.replay().stream().map(WatchEvent::seq).toList();
    assertThat(seqs).hasSize(threads * each);
    // Appended in the same order they were numbered: any inversion here is the race above.
    assertThat(seqs).isSorted();
    assertThat(seqs.get(seqs.size() - 1)).isEqualTo((long) threads * each);
  }
}
