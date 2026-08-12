package io.github.maoyouaa.aegisroute.domain.rollout;

public enum RolloutAction {
  START_SHADOW,
  MARK_ELIGIBLE,
  MARK_BLOCKED,
  APPROVE_CANARY,
  PAUSE,
  ROLLBACK,
  CONFIRM_ROLLBACK
}
