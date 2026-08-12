package io.github.maoyouaa.aegisroute.domain.rollout;

import java.time.Instant;
import java.util.UUID;

public record EvidenceWindow(
    UUID rolloutId,
    Instant windowStart,
    Instant windowEnd,
    int candidateRequests,
    int candidateErrors) {
  public EvidenceWindow {
    if (rolloutId == null || windowStart == null || windowEnd == null) {
      throw new IllegalArgumentException("rollout and window bounds are required");
    }
    if (!windowEnd.isAfter(windowStart)) {
      throw new IllegalArgumentException("windowEnd must be after windowStart");
    }
    if (candidateRequests < 0 || candidateErrors < 0 || candidateErrors > candidateRequests) {
      throw new IllegalArgumentException("invalid candidate counts");
    }
  }

  public double errorRate() {
    return candidateRequests == 0 ? 0.0 : (double) candidateErrors / candidateRequests;
  }
}
