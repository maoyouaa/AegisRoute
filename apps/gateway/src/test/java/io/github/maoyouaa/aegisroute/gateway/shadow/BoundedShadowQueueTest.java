package io.github.maoyouaa.aegisroute.gateway.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BoundedShadowQueueTest {
  @Test
  void fullQueueDropsWithoutThrowingIntoBaselineCaller() {
    var registry = new SimpleMeterRegistry();
    var queue = new BoundedShadowQueue(1, 10, registry);

    assertThat(queue.offer(new ShadowEnvelope("topic", "one", new byte[5]))).isTrue();
    assertThat(queue.offer(new ShadowEnvelope("topic", "two", new byte[5]))).isFalse();
    assertThat(
            registry
                .get("aegis.shadow.dropped")
                .tag("reason", "local_queue_full")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void oversizedPayloadIsDroppedBeforeQueueing() {
    var registry = new SimpleMeterRegistry();
    var queue = new BoundedShadowQueue(2, 4, registry);

    assertThat(queue.offer(new ShadowEnvelope("topic", "large", new byte[5]))).isFalse();
    assertThat(queue.size()).isZero();
    assertThat(
            registry
                .get("aegis.shadow.dropped")
                .tag("reason", "payload_too_large")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
