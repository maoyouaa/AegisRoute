package io.github.maoyouaa.aegisroute.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record ServingObservedV1(
    int schemaVersion,
    UUID eventId,
    String requestId,
    UUID rolloutId,
    long routeVersion,
    String deploymentId,
    boolean candidate,
    ObservedOutcome outcome,
    int statusCode,
    long latencyMs,
    Instant observedAt) {}
