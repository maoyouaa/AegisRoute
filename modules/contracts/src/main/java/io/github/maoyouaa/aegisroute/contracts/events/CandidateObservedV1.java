package io.github.maoyouaa.aegisroute.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record CandidateObservedV1(
    int schemaVersion,
    UUID eventId,
    UUID sampleId,
    String requestId,
    UUID rolloutId,
    long routeVersion,
    String deploymentId,
    ObservedOutcome outcome,
    int statusCode,
    long latencyMs,
    boolean schemaValid,
    Instant observedAt) {}
