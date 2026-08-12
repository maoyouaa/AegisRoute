package io.github.maoyouaa.aegisroute.provider;

import java.time.Duration;

public record ProviderCallContext(String requestId, Duration deadline) {
  public ProviderCallContext {
    if (requestId == null || requestId.isBlank() || deadline == null || deadline.isNegative()) {
      throw new IllegalArgumentException("requestId and non-negative deadline are required");
    }
  }
}
