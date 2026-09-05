package io.github.maoyouaa.aegisroute.control.service;

import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutAction;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutTransitions;
import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sole writer of rollback state, decisions and exact convergence targets. */
@Service
public class RollbackService {
  private final RolloutRepository repository;
  private final ReliabilityRepository reliability;

  public RollbackService(RolloutRepository repository, ReliabilityRepository reliability) {
    this.repository = repository;
    this.reliability = reliability;
  }

  @Transactional
  public Result execute(
      RolloutRepository.RolloutRow rollout,
      EvidenceWindowV2 window,
      String actor,
      String reason,
      Instant now) {
    var next = RolloutTransitions.apply(rollout.state(), RolloutAction.ROLLBACK);
    RouteSnapshot from = repository.latestRoute().orElseThrow();
    if (!from.rolloutId().equals(rollout.id()))
      throw ReliabilityRepository.conflict("ROUTE_OWNER_MISMATCH");
    String kind = window == null ? "MANUAL" : "POLICY";
    Object sourcePayload =
        window == null ? Map.of("route", from, "actor", actor, "reason", reason) : window;
    UUID source =
        reliability.saveRollbackSource(rollout.id(), kind, actor, reason, sourcePayload, now);
    RouteSnapshot target =
        RouteSnapshot.create(
            UUID.randomUUID(),
            rollout.id(),
            repository.reserveRouteVersion(),
            from.baselineDeploymentId(),
            from.baselineBaseUrl(),
            from.candidateDeploymentId(),
            from.candidateBaseUrl(),
            0,
            now,
            next,
            0);
    int requests = window == null ? 0 : window.candidateRequests();
    int errors = window == null ? 0 : window.candidateErrors();
    UUID decision =
        repository.saveRollbackDecision(
            rollout.id(),
            from.version(),
            target.version(),
            window == null ? now : window.windowStart(),
            window == null ? now : window.windowEnd(),
            requests,
            errors,
            requests == 0 ? 0 : (double) errors / requests,
            0.05,
            2,
            RouteChecksum.digest(reliability.json(sourcePayload)),
            reliability.expectedInstances(),
            now);
    reliability.decisionRoute(decision, source, target);
    repository.insertRoute(target);
    var updated = repository.updateState(rollout.id(), rollout.version(), next, 0, now);
    repository.audit(
        rollout.id(), kind + "_ROLLBACK", actor, reason, rollout.version(), updated.version(), now);
    return new Result(decision, target, updated);
  }

  public record Result(
      UUID decisionId, RouteSnapshot route, RolloutRepository.RolloutRow rollout) {}
}
