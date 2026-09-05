package io.github.maoyouaa.aegisroute.control.service;

import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import io.github.maoyouaa.aegisroute.control.api.*;
import io.github.maoyouaa.aegisroute.domain.rollout.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {
  private final RolloutRepository repository;
  private final ReliabilityRepository reliability;
  private final RollbackService rollbacks;
  private final java.time.Clock clock;

  public EvidenceService(
      RolloutRepository repository,
      ReliabilityRepository reliability,
      RollbackService rollbacks,
      java.time.Clock clock) {
    this.repository = repository;
    this.reliability = reliability;
    this.rollbacks = rollbacks;
    this.clock = clock;
  }

  public PolicyEvaluationResponse evaluate(UUID rolloutId, EvidenceSubmission submission) {
    throw ReliabilityRepository.conflict("V2_ROUTE_BOUND_EVIDENCE_REQUIRED");
  }

  @Transactional
  public WindowReceipt evaluate(EvidenceWindowV2 window) {
    var rollout =
        repository
            .findForUpdate(window.route().rolloutId())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "ROLLOUT_NOT_FOUND", "Rollout not found"));
    var replay = reliability.replay(window);
    if (replay.isPresent()) return replay.get();
    var source =
        repository
            .route(window.route().routeId())
            .orElseThrow(() -> ReliabilityRepository.conflict("UNKNOWN_ROUTE"));
    if (!source.equals(window.route()))
      throw ReliabilityRepository.conflict("WINDOW_ROUTE_MISMATCH");
    Instant now = clock.instant();
    if (window.windowEnd().isAfter(now)) throw ReliabilityRepository.conflict("FUTURE_WINDOW");
    if (!window.windowEnd().isAfter(source.createdAt()))
      throw ReliabilityRepository.conflict("WINDOW_PREDATES_ROUTE");
    var previous = reliability.previous(source.routeId());
    if (previous.isPresent() && window.windowStart().isBefore(previous.get().end())) {
      throw ReliabilityRepository.conflict("OUT_OF_ORDER_OR_OVERLAPPING_WINDOW");
    }
    var current = repository.latestRoute().orElseThrow();
    boolean currentRoute = current.routeId().equals(source.routeId());
    var coverage = reliability.coverage(window);
    if (currentRoute && coverage.awaiting() && now.isBefore(window.windowEnd().plusSeconds(90))) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "COVERAGE_PENDING", "Waiting for closed gateway reports");
    }
    boolean sane =
        window.servingObserved() <= coverage.total()
            && window.candidateRequests() <= coverage.candidate()
            && window.completePairs() <= coverage.shadow();
    double fraction =
        coverage.known() && coverage.total() > 0
            ? (double) window.servingObserved() / coverage.total()
            : 0;
    boolean sufficient = coverage.known() && sane && fraction >= 0.95;
    boolean fresh = now.isBefore(window.windowEnd().plusSeconds(120));
    boolean phaseAllows =
        rollout.state() == RolloutState.SHADOW
            || rollout.state() == RolloutState.ELIGIBLE
            || rollout.state() == RolloutState.CANARY
            || rollout.state() == RolloutState.FULL;
    String status =
        !currentRoute
            ? "STALE_ROUTE"
            : !fresh
                ? "EXPIRED"
                : !phaseAllows ? "INACTIVE" : !sufficient ? "INSUFFICIENT_EVIDENCE" : "ACCEPTED";
    boolean actionable = status.equals("ACCEPTED");
    boolean eligible =
        actionable
            && window.completePairs() >= 10
            && coverage.shadow() > 0
            && (double) window.completePairs() / coverage.shadow() >= 0.95
            && window.baselineErrors() == 0
            && (double) window.shadowErrors() / window.completePairs() <= 0.05
            && window.pendingSamples() == 0;
    boolean candidateCovered =
        coverage.candidate() > 0
            && (double) window.candidateRequests() / coverage.candidate() >= 0.95;
    if (rollout.state() == RolloutState.CANARY) {
      eligible =
          eligible
              && candidateCovered
              && window.candidateRequests() >= 10
              && (double) window.candidateErrors() / window.candidateRequests() <= 0.05;
    }
    boolean breached =
        actionable
            && candidateCovered
            && window.candidateRequests() >= 10
            && (double) window.candidateErrors() / window.candidateRequests() > 0.05
            && (rollout.state() == RolloutState.CANARY || rollout.state() == RolloutState.FULL);
    int preceding =
        previous
            .filter(value -> value.end().equals(window.windowStart()))
            .map(ReliabilityRepository.Previous::consecutive)
            .orElse(0);
    int consecutive = breached ? preceding + 1 : 0;

    reliability.saveWindow(window, now);
    reliability.savePolicy(
        window.windowId(), status, eligible, breached, consecutive, fraction, now);
    UUID decision = null;
    long targetVersion = 0;
    if (consecutive >= 3) {
      var rollback =
          rollbacks.execute(
              rollout,
              window,
              "deterministic-policy-v2",
              "Three adjacent sufficient breached windows on the current route",
              now);
      decision = rollback.decisionId();
      targetVersion = rollback.route().version();
    } else if (eligible && rollout.state() == RolloutState.SHADOW) {
      var updated =
          repository.updateState(rollout.id(), rollout.version(), RolloutState.ELIGIBLE, 0, now);
      repository.audit(
          rollout.id(),
          "MARK_ELIGIBLE",
          "deterministic-policy-v2",
          "Fresh route-bound window " + window.windowId(),
          rollout.version(),
          updated.version(),
          now);
    }
    var result =
        new WindowReceipt(
            window.windowId(),
            status,
            eligible,
            breached,
            consecutive,
            decision,
            targetVersion,
            coverage.total(),
            window.servingObserved(),
            coverage.shadow(),
            window.completePairs(),
            window.unpairedSamples(),
            coverage.dropped(),
            window.pendingSamples(),
            fraction);
    reliability.saveResult(result, now);
    return result;
  }

  @Scheduled(fixedDelay = 500)
  @Transactional
  public void detectConvergence() {
    Instant now = clock.instant();
    for (var decision : repository.pendingDecisions()) {
      var rollout = repository.findForUpdate(decision.rolloutId()).orElseThrow();
      if (rollout.state() != RolloutState.ROLLBACK_PROPAGATING) continue;
      List<String> required = repository.decisionTargets(decision.decisionId());
      List<String> converged =
          repository.convergedTargets(decision.decisionId(), decision.targetRouteVersion());
      if (required.isEmpty() || !converged.containsAll(required)) continue;
      repository.saveConvergence(
          rollout.id(),
          decision.targetRouteVersion(),
          reliability.json(required),
          reliability.json(converged),
          now);
      var updated =
          repository.updateState(rollout.id(), rollout.version(), RolloutState.ROLLED_BACK, 0, now);
      repository.audit(
          rollout.id(),
          "ROLLBACK_CONVERGED",
          "control",
          "Every target applied the exact rollback route",
          rollout.version(),
          updated.version(),
          now);
    }
  }
}
