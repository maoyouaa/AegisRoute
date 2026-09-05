package io.github.maoyouaa.aegisroute.contracts.events;

import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public record SampleIdentity(
    String gatewayInstanceId,
    UUID bootId,
    UUID sampleId,
    String requestId,
    RouteSnapshot route,
    Instant admittedAt,
    boolean shadowSelected) {
  public SampleIdentity {
    if (gatewayInstanceId == null
        || !gatewayInstanceId.matches("[a-zA-Z0-9_.-]{1,128}")
        || bootId == null
        || sampleId == null
        || requestId == null
        || requestId.isBlank()
        || requestId.length() > 200
        || route == null
        || !route.validChecksum()
        || admittedAt == null
        || admittedAt.isBefore(route.createdAt())) {
      throw new IllegalArgumentException("invalid sample identity or route checksum");
    }
    boolean candidate =
        io.github.maoyouaa.aegisroute.domain.routing.StableSampler.selectsCandidate(
            requestId, route.candidateRatio());
    if (shadowSelected != route.shadows(requestId, candidate)) {
      throw new IllegalArgumentException("sample does not match immutable routing policy");
    }
  }

  public String key() {
    return gatewayInstanceId + "/" + bootId + "/" + sampleId;
  }

  public Instant windowStart() {
    return bucket(admittedAt);
  }

  public UUID windowId() {
    return windowId(route.routeId(), windowStart());
  }

  public static Instant bucket(Instant value) {
    return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(), 5) * 5);
  }

  public static UUID windowId(UUID routeId, Instant start) {
    return UUID.nameUUIDFromBytes(
        ("evidence-v2/" + routeId + "/" + start).getBytes(StandardCharsets.UTF_8));
  }
}
