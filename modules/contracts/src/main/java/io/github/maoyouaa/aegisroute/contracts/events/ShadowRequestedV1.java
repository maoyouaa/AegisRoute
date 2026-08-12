package io.github.maoyouaa.aegisroute.contracts.events;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import java.time.Instant;
import java.util.UUID;

public record ShadowRequestedV1(
    int schemaVersion,
    UUID eventId,
    UUID sampleId,
    String requestId,
    UUID rolloutId,
    long routeVersion,
    String candidateDeploymentId,
    Instant requestedAt,
    ChatCompletionRequest request) {
  public ShadowRequestedV1 {
    if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion must be 1");
  }
}
