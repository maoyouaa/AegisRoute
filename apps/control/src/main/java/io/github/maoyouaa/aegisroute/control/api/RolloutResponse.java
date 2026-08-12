package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import java.time.Instant;
import java.util.UUID;

public record RolloutResponse(
    UUID id,
    String name,
    RolloutState state,
    long version,
    int candidateRatio,
    long routeVersion,
    Instant updatedAt) {}
