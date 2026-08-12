package io.github.maoyouaa.aegisroute.control.api;

import java.time.Instant;
import java.util.UUID;

public record RouteAppliedRequest(
    String gatewayInstanceId,
    UUID routeId,
    long routeVersion,
    String checksum,
    Instant appliedAt) {}
