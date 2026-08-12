package io.github.maoyouaa.aegisroute.domain.rollout;

public final class InvalidRolloutTransitionException extends RuntimeException {
  public InvalidRolloutTransitionException(RolloutState from, RolloutAction action) {
    super("Cannot apply " + action + " while rollout is " + from);
  }
}
