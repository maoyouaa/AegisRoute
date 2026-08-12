package io.github.maoyouaa.aegisroute.domain.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RolloutTransitionsTest {
  @Test
  void followsHumanPromotionPath() {
    assertThat(RolloutTransitions.apply(RolloutState.DRAFT, RolloutAction.START_SHADOW))
        .isEqualTo(RolloutState.SHADOW);
    assertThat(RolloutTransitions.apply(RolloutState.SHADOW, RolloutAction.MARK_ELIGIBLE))
        .isEqualTo(RolloutState.ELIGIBLE);
    assertThat(RolloutTransitions.apply(RolloutState.ELIGIBLE, RolloutAction.APPROVE_CANARY))
        .isEqualTo(RolloutState.CANARY);
  }

  @Test
  void rejectsAutomaticStylePromotionFromShadow() {
    assertThatThrownBy(
            () -> RolloutTransitions.apply(RolloutState.SHADOW, RolloutAction.APPROVE_CANARY))
        .isInstanceOf(InvalidRolloutTransitionException.class);
  }

  @Test
  void confirmsRollbackOnlyAfterPropagation() {
    assertThat(
            RolloutTransitions.apply(
                RolloutState.ROLLBACK_PROPAGATING, RolloutAction.CONFIRM_ROLLBACK))
        .isEqualTo(RolloutState.ROLLED_BACK);
  }

  @Test
  void canaryStepsCannotBeSkipped() {
    assertThat(RolloutTransitions.approveCanary(RolloutState.ELIGIBLE, 0, 1))
        .isEqualTo(RolloutState.CANARY);
    assertThatThrownBy(() -> RolloutTransitions.approveCanary(RolloutState.CANARY, 1, 50))
        .isInstanceOf(InvalidRolloutTransitionException.class);
    assertThat(RolloutTransitions.approveCanary(RolloutState.CANARY, 50, 100))
        .isEqualTo(RolloutState.FULL);
  }
}
