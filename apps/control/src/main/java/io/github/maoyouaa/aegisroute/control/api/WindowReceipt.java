package io.github.maoyouaa.aegisroute.control.api;

import java.util.UUID;

public record WindowReceipt(
    UUID windowId,
    String status,
    boolean eligible,
    boolean breached,
    int consecutiveBreaches,
    UUID decisionId,
    long targetRouteVersion,
    long expectedServing,
    int observedServing,
    long expectedShadow,
    int completePairs,
    int unpairedSamples,
    long droppedEvents,
    int pendingSamples,
    double coverage) {}
