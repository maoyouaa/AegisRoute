package io.github.maoyouaa.aegisroute.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResultPairingStoreTest {
  @Test
  void pairsBySampleAndDeduplicatesEvent() {
    UUID sample = UUID.randomUUID();
    UUID rollout = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    var store = new ResultPairingStore(Duration.ofMinutes(10), Clock.fixed(now, ZoneOffset.UTC));
    var baseline =
        new BaselineObservedV1(
            1,
            UUID.randomUUID(),
            sample,
            "request",
            rollout,
            1,
            "baseline",
            ObservedOutcome.SUCCESS,
            200,
            10,
            now);
    var candidate =
        new CandidateObservedV1(
            1,
            UUID.randomUUID(),
            sample,
            "request",
            rollout,
            1,
            "candidate",
            ObservedOutcome.SUCCESS,
            200,
            12,
            true,
            now);

    assertThat(store.baseline(baseline)).isEmpty();
    assertThat(store.baseline(baseline)).isEmpty();
    assertThat(store.candidate(candidate)).isPresent();
  }
}
