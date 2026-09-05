package io.github.maoyouaa.aegisroute.worker;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.api.*;
import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableEvidenceStoreTest {
  @TempDir Path directory;
  final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final Instant admitted = Instant.now().minusSeconds(10);
  final RouteSnapshot route = route(UUID.randomUUID(), 1);
  final SampleIdentity sample =
      new SampleIdentity(
          "gateway-a",
          UUID.randomUUID(),
          UUID.randomUUID(),
          "synthetic-request",
          route,
          admitted,
          true);

  @Test
  void newEventIdsAndReorderedObservationsCountTheBusinessSampleOnce() throws Exception {
    try (var store = open()) {
      var candidate = observation(sample, ObservationV2.Kind.SHADOW, "candidate");
      store.observed(candidate, bytes(candidate));
      var baseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(baseline, bytes(baseline));
      var duplicateShadow = observation(sample, ObservationV2.Kind.SHADOW, "candidate");
      var duplicateBaseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(duplicateShadow, bytes(duplicateShadow));
      store.observed(duplicateBaseline, bytes(duplicateBaseline));
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      var window = window(store);
      assertThat(window.completePairs()).isEqualTo(1);
      assertThat(window.servingObserved()).isEqualTo(1);
      assertThat(store.count("samples")).isEqualTo(1);
      assertThat(store.count("records")).isEqualTo(2);
    }
  }

  @Test
  void wrongRouteSameSampleIsQuarantinedWithoutPairing() throws Exception {
    try (var store = open()) {
      var baseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(baseline, bytes(baseline));
      var other =
          new SampleIdentity(
              sample.gatewayInstanceId(),
              sample.bootId(),
              sample.sampleId(),
              sample.requestId(),
              route(UUID.randomUUID(), 2),
              admitted,
              true);
      var candidate = observation(other, ObservationV2.Kind.SHADOW, "candidate");
      store.observed(candidate, bytes(candidate));
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      assertThat(window(store).completePairs()).isZero();
      assertThat(window(store).unpairedSamples()).isEqualTo(1);
      assertThat(store.count("quarantine")).isEqualTo(1);
    }
  }

  @Test
  void durableResultAndPendingWindowSurviveReopenUntilExplicitControlReceipt() throws Exception {
    byte[] sealed;
    byte[] result;
    String windowId;
    try (var store = open()) {
      var request =
          new ShadowRequestedV2(
              2,
              UUID.randomUUID(),
              sample,
              new ChatCompletionRequest(
                  "synthetic", List.of(new ChatMessage("user", "synthetic")), false, null));
      store.requested(request, bytes(request));
      assertThat(store.pendingExecutions(10)).hasSize(1);
      var candidate = observation(sample, ObservationV2.Kind.SHADOW, "candidate");
      result = bytes(candidate);
      store.saveResult(sample.key(), candidate, result);
      store.observed(candidate, result);
      var baseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(baseline, bytes(baseline));
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      sealed = store.pendingWindows().getFirst().payload();
      windowId = store.pendingWindows().getFirst().key();
    }
    try (var restarted = open()) {
      assertThat(restarted.pendingExecutions(10)).isEmpty();
      assertThat(restarted.pendingResults(10).getFirst().payload()).isEqualTo(result);
      assertThat(restarted.pendingWindows().getFirst().payload()).isEqualTo(sealed);
      restarted.resultPublished(sample.key());
      restarted.acknowledgeWindow(windowId, "{\"status\":\"ACCEPTED\"}");
    }
    try (var restartedAgain = open()) {
      assertThat(restartedAgain.pendingResults(10)).isEmpty();
      assertThat(restartedAgain.pendingWindows()).isEmpty();
      assertThat(restartedAgain.count("windows")).isEqualTo(1);
    }
  }

  @Test
  void lateObservationCannotMutateSealedInsufficientWindow() throws Exception {
    try (var store = open()) {
      var baseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(baseline, bytes(baseline));
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      byte[] sealed = store.pendingWindows().getFirst().payload();
      var late = observation(sample, ObservationV2.Kind.SHADOW, "candidate");
      store.observed(late, bytes(late));
      store.seal(Instant.now().plusSeconds(50), Duration.ofSeconds(35));
      assertThat(store.pendingWindows()).hasSize(1);
      assertThat(store.pendingWindows().getFirst().payload()).isEqualTo(sealed);
      assertThat(window(store).completePairs()).isZero();
      assertThat(store.count("quarantine")).isEqualTo(1);
    }
  }

  @Test
  void wrongCandidateIsRejectedBeforeStoreMutation() {
    assertThatThrownBy(() -> observation(sample, ObservationV2.Kind.SHADOW, "wrong-candidate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mismatch");
  }

  @Test
  void overdueWindowsPublishChronologicallyAfterReopen() throws Exception {
    var start = sample.windowStart().minusSeconds(30);
    var oldRoute =
        RouteSnapshot.create(
            route.routeId(),
            route.rolloutId(),
            route.version(),
            "baseline",
            "http://baseline:8080",
            "candidate",
            "http://candidate:8080",
            0,
            start.minusSeconds(5),
            RolloutState.SHADOW,
            100);
    try (var store = open()) {
      for (int offset : new int[] {15, 0, 10, 5}) {
        var identity =
            new SampleIdentity(
                "gateway-a",
                sample.bootId(),
                UUID.randomUUID(),
                "synthetic-" + offset,
                oldRoute,
                start.plusSeconds(offset),
                true);
        var value = observation(identity, ObservationV2.Kind.BASELINE, "baseline");
        store.observed(value, bytes(value));
      }
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
    }
    try (var store = open()) {
      assertThat(
              store.pendingWindows().stream()
                  .map(p -> store.read(p.payload(), EvidenceWindowV2.class).windowStart()))
          .containsExactly(
              start, start.plusSeconds(5), start.plusSeconds(10), start.plusSeconds(15));
    }
  }

  @Test
  void sealedBusinessReplayWithNewEventIdsDoesNotQuarantineOrChangeCounts() throws Exception {
    var input =
        new ChatCompletionRequest(
            "synthetic", List.of(new ChatMessage("user", "synthetic")), false, null);
    byte[] sealed;
    try (var store = open()) {
      var requested = new ShadowRequestedV2(2, UUID.randomUUID(), sample, input);
      store.requested(requested, bytes(requested));
      for (var kind : List.of(ObservationV2.Kind.BASELINE, ObservationV2.Kind.SHADOW)) {
        var value =
            observation(
                sample, kind, kind == ObservationV2.Kind.BASELINE ? "baseline" : "candidate");
        store.observed(value, bytes(value));
      }
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      sealed = store.pendingWindows().getFirst().payload();
    }
    try (var store = open()) {
      var replay = new ShadowRequestedV2(2, UUID.randomUUID(), sample, input);
      store.requested(replay, bytes(replay));
      for (var kind : List.of(ObservationV2.Kind.BASELINE, ObservationV2.Kind.SHADOW)) {
        var value =
            observation(
                sample, kind, kind == ObservationV2.Kind.BASELINE ? "baseline" : "candidate");
        store.observed(value, bytes(value));
      }
      assertThat(store.count("records")).isEqualTo(3);
      assertThat(store.count("samples")).isEqualTo(1);
      assertThat(store.count("quarantine")).isZero();
      assertThat(store.pendingWindows().getFirst().payload()).isEqualTo(sealed);
    }
  }

  @Test
  void differentSnapshotCannotShareWindowEvenWithDifferentSampleKey() throws Exception {
    try (var store = open()) {
      var first = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(first, bytes(first));
      var wrong =
          RouteSnapshot.create(
              route.routeId(),
              route.rolloutId(),
              route.version(),
              "baseline",
              "http://baseline:8080",
              "other",
              "http://other:8080",
              0,
              route.createdAt(),
              RolloutState.SHADOW,
              100);
      var second =
          new SampleIdentity(
              "gateway-b", UUID.randomUUID(), UUID.randomUUID(), "other", wrong, admitted, true);
      var value = observation(second, ObservationV2.Kind.SHADOW, "other");
      store.observed(value, bytes(value));
      assertThat(store.count("samples")).isEqualTo(1);
      assertThat(store.count("quarantine")).isEqualTo(1);
    }
  }

  @Test
  void unpairedBaselineFailureRemainsInEligibilityErrorCount() throws Exception {
    try (var store = open()) {
      for (int i = 0; i < 20; i++) {
        var identity =
            new SampleIdentity(
                "gateway-a",
                sample.bootId(),
                UUID.randomUUID(),
                "synthetic-" + i,
                route,
                admitted,
                true);
        var baseline =
            new ObservationV2(
                2,
                UUID.randomUUID(),
                identity,
                ObservationV2.Kind.BASELINE,
                "baseline",
                i == 19 ? ObservedOutcome.HTTP_ERROR : ObservedOutcome.SUCCESS,
                i == 19 ? 502 : 200,
                10,
                admitted.plusMillis(10));
        store.observed(baseline, bytes(baseline));
        if (i < 19) {
          var shadow = observation(identity, ObservationV2.Kind.SHADOW, "candidate");
          store.observed(shadow, bytes(shadow));
        }
      }
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      assertThat(window(store).completePairs()).isEqualTo(19);
      assertThat(window(store).baselineErrors()).isEqualTo(1);
      assertThat(window(store).unpairedSamples()).isEqualTo(1);
    }
  }

  @Test
  void sealingExpiresUnstartedExecutionButFreshWorkRemainsAvailable() throws Exception {
    var input =
        new ChatCompletionRequest(
            "synthetic", List.of(new ChatMessage("user", "synthetic")), false, null);
    try (var store = open()) {
      var old = new ShadowRequestedV2(2, UUID.randomUUID(), sample, input);
      store.requested(old, bytes(old));
      assertThat(store.executionOpen(sample.key())).isTrue();
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      assertThat(store.executionOpen(sample.key())).isFalse();
      assertThat(store.pendingExecutions(32)).isEmpty();
      var fresh =
          new SampleIdentity(
              "gateway-a", sample.bootId(), UUID.randomUUID(), "fresh", route, Instant.now(), true);
      var next = new ShadowRequestedV2(2, UUID.randomUUID(), fresh, input);
      store.requested(next, bytes(next));
      assertThat(store.pendingExecutions(32))
          .extracting(DurableEvidenceStore.Work::key)
          .containsExactly(fresh.key());
      assertThat(window(store).pendingSamples()).isEqualTo(1);
    }
  }

  @Test
  void savedResultPairsAfterRestartEvenWhenSealRunsBeforeBrokerRoundTrip() throws Exception {
    try (var store = open()) {
      var input =
          new ChatCompletionRequest(
              "synthetic", List.of(new ChatMessage("user", "synthetic")), false, null);
      var request = new ShadowRequestedV2(2, UUID.randomUUID(), sample, input);
      store.requested(request, bytes(request));
      var baseline = observation(sample, ObservationV2.Kind.BASELINE, "baseline");
      store.observed(baseline, bytes(baseline));
      var result = observation(sample, ObservationV2.Kind.SHADOW, "candidate");
      store.saveResult(sample.key(), result, bytes(result));
    }
    try (var store = open()) {
      store.seal(Instant.now().plusSeconds(40), Duration.ofSeconds(35));
      byte[] sealed = store.pendingWindows().getFirst().payload();
      assertThat(window(store).completePairs()).isEqualTo(1);
      assertThat(window(store).pendingSamples()).isZero();
      var result = store.pendingResults(10).getFirst();
      store.observed(store.read(result.payload(), ObservationV2.class), result.payload());
      assertThat(store.pendingWindows().getFirst().payload()).isEqualTo(sealed);
      assertThat(store.count("quarantine")).isZero();
    }
  }

  private DurableEvidenceStore open() {
    return new DurableEvidenceStore(directory.resolve("evidence.sqlite"), mapper);
  }

  private EvidenceWindowV2 window(DurableEvidenceStore store) {
    return store.read(store.pendingWindows().getFirst().payload(), EvidenceWindowV2.class);
  }

  private byte[] bytes(Object value) throws Exception {
    return mapper.writeValueAsBytes(value);
  }

  private ObservationV2 observation(
      SampleIdentity identity, ObservationV2.Kind kind, String deployment) {
    return new ObservationV2(
        2,
        UUID.randomUUID(),
        identity,
        kind,
        deployment,
        ObservedOutcome.SUCCESS,
        200,
        10,
        admitted.plusMillis(10));
  }

  private RouteSnapshot route(UUID rollout, long version) {
    return RouteSnapshot.create(
        UUID.randomUUID(),
        rollout,
        version,
        "baseline",
        "http://baseline:8080",
        "candidate",
        "http://candidate:8080",
        0,
        admitted.minusSeconds(5),
        RolloutState.SHADOW,
        100);
  }
}
