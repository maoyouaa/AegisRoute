package io.github.maoyouaa.aegisroute.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record RouteAppliedV1(
    int schemaVersion,
    UUID eventId,
    String gatewayInstanceId,
    UUID routeId,
    long routeVersion,
    String checksum,
    Instant appliedAt) {}
