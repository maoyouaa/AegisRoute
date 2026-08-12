package io.github.maoyouaa.aegisroute.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShadowEligibilityEvaluatorTest {
  @Test
  void emitsOneDeterministicDecisionAtTheMinimumPairCount() {
    var evaluator = new ShadowEligibilityEvaluator(3, 0.50);
    UUID rolloutId = UUID.randomUUID();

    assertThat(evaluator.record(pair(rolloutId, ObservedOutcome.SUCCESS))).isEmpty();
    assertThat(evaluator.record(pair(rolloutId, ObservedOutcome.HTTP_ERROR))).isEmpty();
    var decision = evaluator.record(pair(rolloutId, ObservedOutcome.SUCCESS));

    assertThat(decision).isPresent();
    assertThat(decision.orElseThrow().eligible()).isTrue();
    assertThat(evaluator.pendingDecisions()).hasSize(1);
    evaluator.markPublished(rolloutId);
    assertThat(evaluator.pendingDecisions()).isEmpty();
    assertThat(evaluator.record(pair(rolloutId, ObservedOutcome.SUCCESS))).isEmpty();
  }

  @Test
  void blocksWhenCandidateErrorRateExceedsThreshold() {
    var evaluator = new ShadowEligibilityEvaluator(2, 0.50);
    UUID rolloutId = UUID.randomUUID();

    evaluator.record(pair(rolloutId, ObservedOutcome.HTTP_ERROR));
    var decision = evaluator.record(pair(rolloutId, ObservedOutcome.HTTP_ERROR));

    assertThat(decision.orElseThrow().eligible()).isFalse();
  }

  private ResultPairingStore.Pair pair(UUID rolloutId, ObservedOutcome candidateOutcome) {
    UUID sampleId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    return new ResultPairingStore.Pair(
        new BaselineObservedV1(
            1,
            UUID.randomUUID(),
            sampleId,
            UUID.randomUUID().toString(),
            rolloutId,
            1,
            "baseline",
            ObservedOutcome.SUCCESS,
            200,
            5,
            now),
        new CandidateObservedV1(
            1,
            UUID.randomUUID(),
            sampleId,
            UUID.randomUUID().toString(),
            rolloutId,
            1,
            "candidate",
            candidateOutcome,
            candidateOutcome == ObservedOutcome.SUCCESS ? 200 : 500,
            5,
            true,
            now));
  }
}
