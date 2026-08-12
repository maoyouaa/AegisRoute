package io.github.maoyouaa.aegisroute.control.api;

public record CreateRolloutRequest(
    String name,
    String baselineDeploymentId,
    String baselineBaseUrl,
    String candidateDeploymentId,
    String candidateBaseUrl,
    String actor,
    String reason) {}
