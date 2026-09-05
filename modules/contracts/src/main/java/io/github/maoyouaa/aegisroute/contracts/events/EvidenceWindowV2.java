package io.github.maoyouaa.aegisroute.contracts.events;

import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.util.UUID;

public record EvidenceWindowV2(
    int schemaVersion,
    UUID windowId,
    long windowVersion,
    RouteSnapshot route,
    Instant windowStart,
    Instant windowEnd,
    int servingObserved,
    int candidateRequests,
    int candidateErrors,
    int completePairs,
    int baselineErrors,
    int shadowErrors,
    int unpairedSamples,
    int pendingSamples,
    String sampleDigest) {
  public EvidenceWindowV2 {
    if (schemaVersion != 2
        || windowVersion != 1
        || route == null
        || !route.validChecksum()
        || windowStart == null
        || !SampleIdentity.bucket(windowStart).equals(windowStart)
        || !windowStart.plusSeconds(5).equals(windowEnd)
        || !SampleIdentity.windowId(route.routeId(), windowStart).equals(windowId)
        || candidateRequests < 0
        || candidateErrors < 0
        || candidateErrors > candidateRequests
        || servingObserved < candidateRequests
        || completePairs > servingObserved - candidateRequests
        || completePairs < 0
        || baselineErrors < 0
        || baselineErrors > servingObserved - candidateRequests
        || shadowErrors < 0
        || shadowErrors > completePairs + unpairedSamples
        || unpairedSamples < 0
        || pendingSamples < 0
        || sampleDigest == null
        || !sampleDigest.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("invalid immutable evidence window");
    }
  }
}
