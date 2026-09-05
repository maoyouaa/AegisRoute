package io.github.maoyouaa.aegisroute.domain.routing;

import static org.assertj.core.api.Assertions.*;

import io.github.maoyouaa.aegisroute.domain.rollout.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShadowLifecycleTest {
  @ParameterizedTest
  @EnumSource(RolloutState.class)
  void policyIsExplicitAndChecksumCoveredForEveryState(RolloutState state) {
    int ratio = state == RolloutState.CANARY ? 10 : state == RolloutState.FULL ? 100 : 0;
    int shadow =
        state == RolloutState.SHADOW
                || state == RolloutState.ELIGIBLE
                || state == RolloutState.CANARY
            ? 100
            : 0;
    var route =
        RouteSnapshot.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "base",
            "http://baseline:8080",
            "candidate",
            "http://candidate:8080",
            ratio,
            Instant.now(),
            state,
            shadow);
    assertThat(route.validChecksum()).isTrue();
    assertThat(route.shadows("synthetic", false)).isEqualTo(shadow == 100);
    assertThat(route.shadows("synthetic", true)).isFalse();
    if (shadow > 0) {
      var tampered =
          new RouteSnapshot(
              route.routeId(),
              route.rolloutId(),
              route.version(),
              route.baselineDeploymentId(),
              route.baselineBaseUrl(),
              route.candidateDeploymentId(),
              route.candidateBaseUrl(),
              ratio,
              route.checksum(),
              route.createdAt(),
              state,
              0,
              2);
      assertThat(tampered.validChecksum()).isFalse();
    }
  }

  @Test
  void invalidStageSentinelCannotPromoteDraftOrFull() {
    for (RolloutState state : new RolloutState[] {RolloutState.DRAFT, RolloutState.FULL}) {
      assertThatThrownBy(() -> RolloutTransitions.approveCanary(state, 0, -1))
          .isInstanceOf(InvalidRolloutTransitionException.class);
    }
  }
}
