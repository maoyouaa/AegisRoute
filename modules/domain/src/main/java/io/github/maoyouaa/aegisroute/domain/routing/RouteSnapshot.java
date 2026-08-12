package io.github.maoyouaa.aegisroute.domain.routing;

import java.time.Instant;
import java.util.UUID;

public record RouteSnapshot(
    UUID routeId,
    UUID rolloutId,
    long version,
    String baselineDeploymentId,
    String baselineBaseUrl,
    String candidateDeploymentId,
    String candidateBaseUrl,
    int candidateRatio,
    String checksum,
    Instant createdAt) {
  public RouteSnapshot {
    if (routeId == null || rolloutId == null || version < 1 || createdAt == null) {
      throw new IllegalArgumentException(
          "routeId, rolloutId, positive version and createdAt are required");
    }
    if (baselineDeploymentId == null
        || baselineDeploymentId.isBlank()
        || baselineBaseUrl == null
        || baselineBaseUrl.isBlank()
        || candidateDeploymentId == null
        || candidateDeploymentId.isBlank()
        || candidateBaseUrl == null
        || candidateBaseUrl.isBlank()) {
      throw new IllegalArgumentException("baseline and candidate deployment details are required");
    }
    if (candidateRatio < 0 || candidateRatio > 100) {
      throw new IllegalArgumentException("candidateRatio must be between 0 and 100");
    }
    if (checksum == null || !checksum.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("checksum must be a lowercase SHA-256 digest");
    }
  }
}
