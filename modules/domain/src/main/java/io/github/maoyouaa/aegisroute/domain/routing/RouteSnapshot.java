package io.github.maoyouaa.aegisroute.domain.routing;

import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
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
    Instant createdAt,
    RolloutState phase,
    int shadowPercentage,
    int checksumVersion) {
  public RouteSnapshot(
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
    this(
        routeId,
        rolloutId,
        version,
        baselineDeploymentId,
        baselineBaseUrl,
        candidateDeploymentId,
        candidateBaseUrl,
        candidateRatio,
        checksum,
        createdAt,
        candidateRatio == 0 ? RolloutState.DRAFT : RolloutState.CANARY,
        0,
        1);
  }

  public RouteSnapshot {
    if (checksumVersion == 0) checksumVersion = 1;
    if (phase == null) phase = RolloutState.DRAFT;
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
    if (checksumVersion < 1
        || checksumVersion > 2
        || shadowPercentage < 0
        || shadowPercentage > 100) {
      throw new IllegalArgumentException("invalid snapshot policy version or shadow percentage");
    }
    if (checksumVersion == 1 && shadowPercentage != 0) {
      throw new IllegalArgumentException("legacy snapshots cannot enable shadow");
    }
    if (shadowPercentage > 0
        && phase != RolloutState.SHADOW
        && phase != RolloutState.ELIGIBLE
        && phase != RolloutState.CANARY) {
      throw new IllegalArgumentException("shadow is disabled in this phase");
    }
    if (checksumVersion == 2
        && candidateRatio > 0
        && phase != RolloutState.CANARY
        && phase != RolloutState.FULL) {
      throw new IllegalArgumentException("candidate serving is disabled in this phase");
    }
  }

  public boolean shadows(String requestId, boolean candidateServed) {
    return !candidateServed
        && checksumVersion == 2
        && shadowPercentage > 0
        && StableSampler.selectsCandidate("shadow:" + requestId, shadowPercentage);
  }

  public boolean validChecksum() {
    return checksum.equals(RouteChecksum.calculate(this));
  }

  public static RouteSnapshot create(
      UUID routeId,
      UUID rolloutId,
      long version,
      String baselineId,
      String baselineUrl,
      String candidateId,
      String candidateUrl,
      int ratio,
      Instant now,
      RolloutState phase,
      int shadowPercentage) {
    var unsigned =
        new RouteSnapshot(
            routeId,
            rolloutId,
            version,
            baselineId,
            baselineUrl,
            candidateId,
            candidateUrl,
            ratio,
            "0".repeat(64),
            now,
            phase,
            shadowPercentage,
            2);
    return new RouteSnapshot(
        routeId,
        rolloutId,
        version,
        baselineId,
        baselineUrl,
        candidateId,
        candidateUrl,
        ratio,
        RouteChecksum.calculate(unsigned),
        now,
        phase,
        shadowPercentage,
        2);
  }
}
