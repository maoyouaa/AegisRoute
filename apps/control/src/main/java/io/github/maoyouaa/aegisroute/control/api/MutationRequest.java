package io.github.maoyouaa.aegisroute.control.api;

public record MutationRequest(String actor, String reason, Integer candidateRatio) {}
