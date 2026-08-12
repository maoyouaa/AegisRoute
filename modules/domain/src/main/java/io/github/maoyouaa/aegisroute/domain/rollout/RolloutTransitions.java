package io.github.maoyouaa.aegisroute.domain.rollout;

import java.util.EnumSet;

public final class RolloutTransitions {
  private static final EnumSet<RolloutState> PAUSABLE =
      EnumSet.of(
          RolloutState.SHADOW,
          RolloutState.ELIGIBLE,
          RolloutState.BLOCKED,
          RolloutState.CANARY,
          RolloutState.FULL);
  private static final EnumSet<RolloutState> ROLLBACKABLE =
      EnumSet.of(RolloutState.CANARY, RolloutState.FULL, RolloutState.PAUSED);

  private RolloutTransitions() {}

  public static RolloutState apply(RolloutState current, RolloutAction action) {
    return switch (action) {
      case START_SHADOW -> require(current, RolloutState.DRAFT, RolloutState.SHADOW, action);
      case MARK_ELIGIBLE -> require(current, RolloutState.SHADOW, RolloutState.ELIGIBLE, action);
      case MARK_BLOCKED -> require(current, RolloutState.SHADOW, RolloutState.BLOCKED, action);
      case APPROVE_CANARY -> {
        if (current == RolloutState.ELIGIBLE || current == RolloutState.CANARY) {
          yield RolloutState.CANARY;
        }
        throw new InvalidRolloutTransitionException(current, action);
      }
      case PAUSE -> {
        if (PAUSABLE.contains(current)) {
          yield RolloutState.PAUSED;
        }
        throw new InvalidRolloutTransitionException(current, action);
      }
      case ROLLBACK -> {
        if (ROLLBACKABLE.contains(current)) {
          yield RolloutState.ROLLBACK_PROPAGATING;
        }
        throw new InvalidRolloutTransitionException(current, action);
      }
      case CONFIRM_ROLLBACK ->
          require(current, RolloutState.ROLLBACK_PROPAGATING, RolloutState.ROLLED_BACK, action);
    };
  }

  public static RolloutState approveCanary(
      RolloutState current, int currentRatio, int requestedRatio) {
    int[] steps = {1, 10, 50, 100};
    int expected = -1;
    if (current == RolloutState.ELIGIBLE && currentRatio == 0) {
      expected = steps[0];
    } else if (current == RolloutState.CANARY) {
      for (int index = 0; index < steps.length - 1; index++) {
        if (steps[index] == currentRatio) expected = steps[index + 1];
      }
    }
    if (requestedRatio != expected) {
      throw new InvalidRolloutTransitionException(current, RolloutAction.APPROVE_CANARY);
    }
    return requestedRatio == 100 ? RolloutState.FULL : RolloutState.CANARY;
  }

  private static RolloutState require(
      RolloutState actual, RolloutState expected, RolloutState next, RolloutAction action) {
    if (actual != expected) {
      throw new InvalidRolloutTransitionException(actual, action);
    }
    return next;
  }
}
