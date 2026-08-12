package io.github.maoyouaa.aegisroute.worker;

import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShadowEligibilityEvaluator {
  private final int minimumPairs;
  private final double maximumCandidateErrorRate;
  private final Map<UUID, Counts> counts = new ConcurrentHashMap<>();
  private final Map<UUID, Decision> pending = new ConcurrentHashMap<>();

  public ShadowEligibilityEvaluator(int minimumPairs, double maximumCandidateErrorRate) {
    if (minimumPairs < 1 || maximumCandidateErrorRate < 0 || maximumCandidateErrorRate > 1) {
      throw new IllegalArgumentException("invalid shadow eligibility policy");
    }
    this.minimumPairs = minimumPairs;
    this.maximumCandidateErrorRate = maximumCandidateErrorRate;
  }

  public synchronized Optional<Decision> record(ResultPairingStore.Pair pair) {
    UUID rolloutId = pair.baseline().rolloutId();
    Counts rollout = counts.computeIfAbsent(rolloutId, ignored -> new Counts());
    if (rollout.decided) return Optional.empty();
    rollout.pairs++;
    if (pair.baseline().outcome() != ObservedOutcome.SUCCESS
        || pair.baseline().statusCode() >= 500) {
      rollout.baselineErrors++;
    }
    if (pair.candidate().outcome() != ObservedOutcome.SUCCESS
        || pair.candidate().statusCode() >= 500) {
      rollout.candidateErrors++;
    }
    if (rollout.pairs < minimumPairs) return Optional.empty();
    rollout.decided = true;
    double candidateErrorRate = (double) rollout.candidateErrors / rollout.pairs;
    boolean eligible =
        rollout.baselineErrors == 0 && candidateErrorRate <= maximumCandidateErrorRate;
    String reason =
        "shadow-policy-v1 pairs="
            + rollout.pairs
            + " baselineErrors="
            + rollout.baselineErrors
            + " candidateErrors="
            + rollout.candidateErrors
            + " candidateErrorRate="
            + candidateErrorRate
            + " threshold="
            + maximumCandidateErrorRate;
    Decision decision = new Decision(rolloutId, eligible, reason);
    pending.put(rolloutId, decision);
    return Optional.of(decision);
  }

  public List<Decision> pendingDecisions() {
    return List.copyOf(pending.values());
  }

  public void markPublished(UUID rolloutId) {
    pending.remove(rolloutId);
  }

  public record Decision(UUID rolloutId, boolean eligible, String reason) {}

  private static final class Counts {
    private int pairs;
    private int baselineErrors;
    private int candidateErrors;
    private boolean decided;
  }
}
