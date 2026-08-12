package io.github.maoyouaa.aegisroute.domain.rollout;

public record RollbackPolicy(
    int version, int minimumCandidateRequests, double errorRateThreshold, int consecutiveBreaches) {
  public RollbackPolicy {
    if (version < 1
        || minimumCandidateRequests < 1
        || errorRateThreshold < 0
        || errorRateThreshold > 1
        || consecutiveBreaches < 1) {
      throw new IllegalArgumentException("invalid rollback policy");
    }
  }

  public boolean breaches(EvidenceWindow evidence) {
    return evidence.candidateRequests() >= minimumCandidateRequests
        && evidence.errorRate() > errorRateThreshold;
  }

  public static RollbackPolicy demonstrationDefault() {
    return new RollbackPolicy(1, 10, 0.05, 3);
  }
}
