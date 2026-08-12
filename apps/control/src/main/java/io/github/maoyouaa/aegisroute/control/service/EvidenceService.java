package io.github.maoyouaa.aegisroute.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.control.api.ApiException;
import io.github.maoyouaa.aegisroute.control.api.EvidenceSubmission;
import io.github.maoyouaa.aegisroute.control.api.PolicyEvaluationResponse;
import io.github.maoyouaa.aegisroute.domain.rollout.EvidenceWindow;
import io.github.maoyouaa.aegisroute.domain.rollout.RollbackPolicy;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutAction;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutTransitions;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {
  private final RolloutRepository repository;
  private final ObjectMapper objectMapper;
  private final Clock clock = Clock.systemUTC();
  private final RollbackPolicy policy = RollbackPolicy.demonstrationDefault();

  public EvidenceService(RolloutRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public PolicyEvaluationResponse evaluate(UUID rolloutId, EvidenceSubmission submission) {
    var rollout =
        repository
            .findForUpdate(rolloutId)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "ROLLOUT_NOT_FOUND", "Rollout not found"));
    if (rollout.state() != RolloutState.CANARY && rollout.state() != RolloutState.FULL) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "ROLLOUT_STATE_CONFLICT",
          "Serving evidence is accepted only during canary or full traffic");
    }
    EvidenceWindow evidence;
    try {
      evidence =
          new EvidenceWindow(
              rolloutId,
              submission.windowStart(),
              submission.windowEnd(),
              submission.candidateRequests(),
              submission.candidateErrors());
    } catch (IllegalArgumentException invalid) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", invalid.getMessage());
    }
    String digest = digest(evidence);
    Instant now = clock.instant();
    UUID evidenceId =
        repository.saveEvidence(
            rolloutId,
            evidence.windowStart(),
            evidence.windowEnd(),
            evidence.candidateRequests(),
            evidence.candidateErrors(),
            evidence.errorRate(),
            digest,
            now);
    boolean breached = policy.breaches(evidence);
    int consecutive = breached ? repository.previousConsecutiveBreaches(rolloutId) + 1 : 0;
    repository.savePolicyEvaluation(
        rolloutId,
        evidenceId,
        policy.version(),
        policy.errorRateThreshold(),
        breached,
        consecutive,
        now);

    if (consecutive < policy.consecutiveBreaches()) {
      return new PolicyEvaluationResponse(evidenceId, breached, consecutive, false, null, 0);
    }

    RouteSnapshot fromRoute = repository.latestRoute().orElseThrow();
    var propagating =
        repository.updateState(
            rollout.id(),
            rollout.version(),
            RolloutTransitions.apply(rollout.state(), RolloutAction.ROLLBACK),
            0,
            now);
    long targetRouteVersion = repository.reserveRouteVersion();
    List<String> activeInstances = repository.activeInstances(now.minus(Duration.ofSeconds(15)));
    UUID decisionId =
        repository.saveRollbackDecision(
            rolloutId,
            fromRoute.version(),
            targetRouteVersion,
            evidence.windowStart(),
            evidence.windowEnd(),
            evidence.candidateRequests(),
            evidence.candidateErrors(),
            evidence.errorRate(),
            policy.errorRateThreshold(),
            policy.version(),
            digest,
            activeInstances,
            now);
    RouteSnapshot toRoute = repository.createRouteRevision(propagating, 0, targetRouteVersion, now);
    repository.audit(
        rolloutId,
        "AUTOMATIC_ROLLBACK",
        "deterministic-policy-v" + policy.version(),
        "Three consecutive breached evidence windows",
        rollout.version(),
        propagating.version(),
        now);
    return new PolicyEvaluationResponse(
        evidenceId, true, consecutive, true, decisionId, toRoute.version());
  }

  @Scheduled(fixedDelay = 500)
  @Transactional
  public void detectConvergence() {
    Instant now = clock.instant();
    for (var decision : repository.pendingDecisions()) {
      List<String> required = repository.decisionTargets(decision.decisionId());
      List<String> converged =
          repository.convergedTargets(decision.decisionId(), decision.targetRouteVersion());
      if (converged.containsAll(required)) {
        try {
          repository.saveConvergence(
              decision.rolloutId(),
              decision.targetRouteVersion(),
              objectMapper.writeValueAsString(required),
              objectMapper.writeValueAsString(converged),
              now);
        } catch (JsonProcessingException e) {
          throw new IllegalStateException("Cannot serialize convergence evidence", e);
        }
        var rollout = repository.find(decision.rolloutId()).orElseThrow();
        if (rollout.state() == RolloutState.ROLLBACK_PROPAGATING) {
          var rolledBack =
              repository.updateState(
                  rollout.id(),
                  rollout.version(),
                  RolloutTransitions.apply(rollout.state(), RolloutAction.CONFIRM_ROLLBACK),
                  0,
                  now);
          repository.audit(
              rollout.id(),
              "ROLLBACK_CONVERGED",
              "control",
              "All decision-time gateways applied the rollback route",
              rollout.version(),
              rolledBack.version(),
              now);
        }
      }
    }
  }

  private String digest(EvidenceWindow evidence) {
    String canonical =
        evidence.rolloutId()
            + "\n"
            + evidence.windowStart()
            + "\n"
            + evidence.windowEnd()
            + "\n"
            + evidence.candidateRequests()
            + "\n"
            + evidence.candidateErrors();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
