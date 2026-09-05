package io.github.maoyouaa.aegisroute.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record ObservationV2(
    int schemaVersion,
    UUID eventId,
    SampleIdentity sample,
    Kind kind,
    String deploymentId,
    ObservedOutcome outcome,
    int statusCode,
    long latencyMs,
    Instant observedAt) {
  public enum Kind {
    BASELINE,
    CANARY,
    SHADOW
  }

  public ObservationV2 {
    if (schemaVersion != 2
        || eventId == null
        || sample == null
        || kind == null
        || outcome == null
        || observedAt == null
        || statusCode < 100
        || statusCode > 599
        || latencyMs < 0) {
      throw new IllegalArgumentException("invalid observation");
    }
    String expected =
        kind == Kind.BASELINE
            ? sample.route().baselineDeploymentId()
            : sample.route().candidateDeploymentId();
    if (!expected.equals(deploymentId)
        || (kind == Kind.SHADOW && !sample.shadowSelected())
        || (kind == Kind.CANARY && sample.shadowSelected())) {
      throw new IllegalArgumentException("observation candidate or shadow identity mismatch");
    }
    boolean candidate =
        io.github.maoyouaa.aegisroute.domain.routing.StableSampler.selectsCandidate(
            sample.requestId(), sample.route().candidateRatio());
    if ((kind == Kind.CANARY && !candidate) || (kind == Kind.BASELINE && candidate)) {
      throw new IllegalArgumentException("serving observation violates routing selection");
    }
  }

  public boolean failed() {
    return outcome != ObservedOutcome.SUCCESS || statusCode >= 400;
  }
}
