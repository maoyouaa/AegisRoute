package io.github.maoyouaa.aegisroute.control.api;

import java.util.UUID;

public record PolicyEvaluationResponse(
    UUID evidenceId,
    boolean breached,
    int consecutiveBreaches,
    boolean rollbackTriggered,
    UUID decisionId,
    long targetRouteVersion) {}
