package be.kleisli.ww.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class StateStreamTest {

  @Test
  @DisplayName("says how many panels are listening, so a costly sampler can slow down")
  void countsSubscribers() {
    StateStream<String> stream = new StateStream<>();
    stream.publish("first");

    // Nobody is looking: ProcessTreeService uses exactly this to stop paying for an `lsof` every
    // two seconds, measured at 110ms a call and therefore 5.5% of a core for a question nobody is
    // asking.
    assertThat(stream.subscribers()).isZero();

    List<String> seen = new ArrayList<>();
    Disposable panel = stream.flux().subscribe(seen::add);

    assertThat(stream.subscribers()).isEqualTo(1);
    // And the panel is not left blank while it waits for the next change.
    assertThat(seen).containsExactly("first");

    panel.dispose();

    assertThat(stream.subscribers()).isZero();
  }
}
