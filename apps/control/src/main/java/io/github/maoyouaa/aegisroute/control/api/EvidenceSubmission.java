package io.github.maoyouaa.aegisroute.control.api;

import java.time.Instant;

public record EvidenceSubmission(
    Instant windowStart, Instant windowEnd, int candidateRequests, int candidateErrors) {}
