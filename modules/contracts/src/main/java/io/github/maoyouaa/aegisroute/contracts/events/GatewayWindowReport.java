package io.github.maoyouaa.aegisroute.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record GatewayWindowReport(
    int schemaVersion,
    String gatewayInstanceId,
    UUID bootId,
    UUID routeId,
    long routeVersion,
    String checksum,
    Instant windowStart,
    Instant windowEnd,
    long totalRequests,
    long candidateRequests,
    long shadowRequests,
    long droppedEvents,
    boolean coverageComplete,
    String coverageReason) {
  public GatewayWindowReport(
      int schemaVersion,
      String gatewayInstanceId,
      UUID bootId,
      UUID routeId,
      long routeVersion,
      String checksum,
      Instant windowStart,
      Instant windowEnd,
      long totalRequests,
      long candidateRequests,
      long shadowRequests,
      long droppedEvents) {
    this(
        schemaVersion,
        gatewayInstanceId,
        bootId,
        routeId,
        routeVersion,
        checksum,
        windowStart,
        windowEnd,
        totalRequests,
        candidateRequests,
        shadowRequests,
        droppedEvents,
        true,
        "COMPLETE");
  }

  public GatewayWindowReport {
    if (coverageReason == null) coverageReason = "UNKNOWN";
    if (coverageComplete && !coverageReason.equals("COMPLETE"))
      throw new IllegalArgumentException("Invalid coverage reason");
    if (schemaVersion != 2
        || gatewayInstanceId == null
        || bootId == null
        || routeId == null
        || routeVersion < 1
        || checksum == null
        || windowStart == null
        || windowEnd == null
        || !SampleIdentity.bucket(windowStart).equals(windowStart)
        || !windowStart.plusSeconds(5).equals(windowEnd)
        || totalRequests < 0
        || candidateRequests < 0
        || shadowRequests < 0
        || droppedEvents < 0
        || candidateRequests + shadowRequests > totalRequests) {
      throw new IllegalArgumentException("invalid gateway window report");
    }
  }
}
