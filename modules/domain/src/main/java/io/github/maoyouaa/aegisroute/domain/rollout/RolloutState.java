package io.github.maoyouaa.aegisroute.domain.rollout;

public enum RolloutState {
  DRAFT,
  SHADOW,
  ELIGIBLE,
  BLOCKED,
  CANARY,
  FULL,
  PAUSED,
  ROLLBACK_PROPAGATING,
  ROLLED_BACK
}
