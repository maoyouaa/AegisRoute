package io.github.maoyouaa.aegisroute.control.service;

import io.github.maoyouaa.aegisroute.control.api.CreateRolloutRequest;
import io.github.maoyouaa.aegisroute.control.api.RolloutResponse;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RolloutRepository {
  private final JdbcClient jdbc;

  public RolloutRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public RolloutRow create(CreateRolloutRequest request, UUID id, Instant now) {
    jdbc.sql(
            """
            INSERT INTO rollouts (
              id, name, state, version, candidate_ratio,
              baseline_deployment_id, baseline_base_url,
              candidate_deployment_id, candidate_base_url, created_at, updated_at
            ) VALUES (:id, :name, 'DRAFT', 1, 0, :baselineId, :baselineUrl,
                      :candidateId, :candidateUrl, :now, :now)
            """)
        .param("id", id)
        .param("name", request.name())
        .param("baselineId", request.baselineDeploymentId())
        .param("baselineUrl", request.baselineBaseUrl())
        .param("candidateId", request.candidateDeploymentId())
        .param("candidateUrl", request.candidateBaseUrl())
        .param("now", timestamp(now))
        .update();
    return find(id).orElseThrow();
  }

  public Optional<RolloutRow> find(UUID id) {
    return jdbc.sql("SELECT * FROM rollouts WHERE id = :id")
        .param("id", id)
        .query(RolloutRepository::map)
        .optional();
  }

  public Optional<RolloutRow> findForUpdate(UUID id) {
    return jdbc.sql("SELECT * FROM rollouts WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(RolloutRepository::map)
        .optional();
  }

  public RolloutRow updateState(
      UUID id, long expectedVersion, RolloutState state, int candidateRatio, Instant now) {
    int changed =
        jdbc.sql(
                """
                UPDATE rollouts SET state = :state, candidate_ratio = :ratio,
                  version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion
                """)
            .param("state", state.name())
            .param("ratio", candidateRatio)
            .param("now", timestamp(now))
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .update();
    if (changed != 1) throw new OptimisticConflictException();
    return find(id).orElseThrow();
  }

  public RouteSnapshot createRouteRevision(RolloutRow rollout, int candidateRatio, Instant now) {
    return createRouteRevision(rollout, candidateRatio, reserveRouteVersion(), now);
  }

  public long reserveRouteVersion() {
    return jdbc.sql("SELECT nextval('route_revision_version_seq')").query(Long.class).single();
  }

  public RouteSnapshot createRouteRevision(
      RolloutRow rollout, int candidateRatio, long version, Instant now) {
    UUID routeId = UUID.randomUUID();
    String checksum =
        io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum.calculate(
            routeId,
            rollout.id(),
            version,
            rollout.baselineDeploymentId(),
            rollout.baselineBaseUrl(),
            rollout.candidateDeploymentId(),
            rollout.candidateBaseUrl(),
            candidateRatio);
    jdbc.sql(
            """
            INSERT INTO route_revisions (
              route_id, rollout_id, version, baseline_deployment_id, baseline_base_url,
              candidate_deployment_id, candidate_base_url, candidate_ratio, checksum, created_at
            ) VALUES (:routeId, :rolloutId, :version, :baselineId, :baselineUrl,
                      :candidateId, :candidateUrl, :ratio, :checksum, :now)
            """)
        .param("routeId", routeId)
        .param("rolloutId", rollout.id())
        .param("version", version)
        .param("baselineId", rollout.baselineDeploymentId())
        .param("baselineUrl", rollout.baselineBaseUrl())
        .param("candidateId", rollout.candidateDeploymentId())
        .param("candidateUrl", rollout.candidateBaseUrl())
        .param("ratio", candidateRatio)
        .param("checksum", checksum)
        .param("now", timestamp(now))
        .update();
    return new RouteSnapshot(
        routeId,
        rollout.id(),
        version,
        rollout.baselineDeploymentId(),
        rollout.baselineBaseUrl(),
        rollout.candidateDeploymentId(),
        rollout.candidateBaseUrl(),
        candidateRatio,
        checksum,
        now);
  }

  public Optional<RouteSnapshot> latestRoute() {
    return jdbc.sql("SELECT * FROM route_revisions ORDER BY version DESC LIMIT 1")
        .query(
            (rs, ignored) ->
                new RouteSnapshot(
                    rs.getObject("route_id", UUID.class),
                    rs.getObject("rollout_id", UUID.class),
                    rs.getLong("version"),
                    rs.getString("baseline_deployment_id"),
                    rs.getString("baseline_base_url"),
                    rs.getString("candidate_deployment_id"),
                    rs.getString("candidate_base_url"),
                    rs.getInt("candidate_ratio"),
                    rs.getString("checksum"),
                    rs.getTimestamp("created_at").toInstant()))
        .optional();
  }

  public void audit(
      UUID rolloutId,
      String action,
      String actor,
      String reason,
      long oldVersion,
      long newVersion,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO rollout_audit_events (
              id, rollout_id, action, actor, reason, old_version, new_version, created_at
            ) VALUES (:id, :rolloutId, :action, :actor, :reason, :oldVersion, :newVersion, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("rolloutId", rolloutId)
        .param("action", action)
        .param("actor", actor)
        .param("reason", reason)
        .param("oldVersion", oldVersion)
        .param("newVersion", newVersion)
        .param("now", timestamp(now))
        .update();
  }

  public Optional<IdempotencyRow> findIdempotency(String key, Instant now) {
    return jdbc.sql(
            "SELECT * FROM idempotency_records WHERE idempotency_key = :key AND expires_at > :now")
        .param("key", key)
        .param("now", timestamp(now))
        .query(
            (rs, ignored) ->
                new IdempotencyRow(
                    rs.getString("request_hash"),
                    rs.getInt("status_code"),
                    rs.getString("response_body")))
        .optional();
  }

  public void lockIdempotencyKey(String key) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", key)
        .query((resultSet, ignored) -> Boolean.TRUE)
        .single();
  }

  public void saveIdempotency(
      String key, String requestHash, int statusCode, String responseBody, Instant now) {
    try {
      jdbc.sql(
              """
              INSERT INTO idempotency_records (
                idempotency_key, request_hash, status_code, response_body, created_at, expires_at
              ) VALUES (:key, :hash, :status, CAST(:body AS jsonb), :now, :expires)
              """)
          .param("key", key)
          .param("hash", requestHash)
          .param("status", statusCode)
          .param("body", responseBody)
          .param("now", timestamp(now))
          .param("expires", timestamp(now.plusSeconds(24 * 60 * 60)))
          .update();
    } catch (DuplicateKeyException ignored) {
      // A concurrent identical request will read the committed record on retry.
    }
  }

  public void acknowledge(
      String instanceId, RouteSnapshot route, Instant appliedAt, Instant seenAt) {
    jdbc.sql(
            """
            INSERT INTO gateway_route_acks (
              gateway_instance_id, route_id, route_version, checksum, applied_at, last_seen_at
            ) VALUES (:instanceId, :routeId, :version, :checksum, :appliedAt, :seenAt)
            ON CONFLICT (gateway_instance_id) DO UPDATE SET
              route_id = EXCLUDED.route_id, route_version = EXCLUDED.route_version,
              checksum = EXCLUDED.checksum, applied_at = EXCLUDED.applied_at,
              last_seen_at = EXCLUDED.last_seen_at
            """)
        .param("instanceId", instanceId)
        .param("routeId", route.routeId())
        .param("version", route.version())
        .param("checksum", route.checksum())
        .param("appliedAt", timestamp(appliedAt))
        .param("seenAt", timestamp(seenAt))
        .update();
  }

  public List<String> activeInstances(Instant activeSince) {
    return jdbc.sql(
            "SELECT gateway_instance_id FROM gateway_route_acks WHERE last_seen_at >= :since")
        .param("since", timestamp(activeSince))
        .query(String.class)
        .list();
  }

  public UUID saveEvidence(
      UUID rolloutId,
      Instant windowStart,
      Instant windowEnd,
      int requests,
      int errors,
      double errorRate,
      String digest,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO evidence_windows (
              id, rollout_id, window_start, window_end, candidate_requests,
              candidate_errors, error_rate, evidence_digest, created_at
            ) VALUES (:id, :rolloutId, :start, :end, :requests, :errors,
                      :rate, :digest, :now)
            """)
        .param("id", id)
        .param("rolloutId", rolloutId)
        .param("start", timestamp(windowStart))
        .param("end", timestamp(windowEnd))
        .param("requests", requests)
        .param("errors", errors)
        .param("rate", errorRate)
        .param("digest", digest)
        .param("now", timestamp(now))
        .update();
    return id;
  }

  public int previousConsecutiveBreaches(UUID rolloutId) {
    return jdbc.sql(
            """
            SELECT COALESCE((
              SELECT consecutive_breaches FROM policy_evaluations
              WHERE rollout_id = :rolloutId ORDER BY created_at DESC LIMIT 1
            ), 0)
            """)
        .param("rolloutId", rolloutId)
        .query(Integer.class)
        .single();
  }

  public void savePolicyEvaluation(
      UUID rolloutId,
      UUID evidenceId,
      int policyVersion,
      double threshold,
      boolean breached,
      int consecutiveBreaches,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO policy_evaluations (
              id, rollout_id, evidence_window_id, policy_version, threshold,
              breached, consecutive_breaches, created_at
            ) VALUES (:id, :rolloutId, :evidenceId, :policyVersion, :threshold,
                      :breached, :consecutive, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("rolloutId", rolloutId)
        .param("evidenceId", evidenceId)
        .param("policyVersion", policyVersion)
        .param("threshold", threshold)
        .param("breached", breached)
        .param("consecutive", consecutiveBreaches)
        .param("now", timestamp(now))
        .update();
  }

  public UUID saveRollbackDecision(
      UUID rolloutId,
      long fromVersion,
      long toVersion,
      Instant windowStart,
      Instant windowEnd,
      int requests,
      int errors,
      double rate,
      double threshold,
      int policyVersion,
      String digest,
      List<String> targetInstances,
      Instant now) {
    UUID decisionId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO rollout_decisions (
              decision_id, rollout_id, decision, from_route_version, to_route_version,
              window_start, window_end, candidate_requests, candidate_errors,
              error_rate, threshold, policy_version, evidence_digest, created_at
            ) VALUES (:decisionId, :rolloutId, 'ROLLBACK', :fromVersion, :toVersion,
                      :start, :end, :requests, :errors, :rate, :threshold,
                      :policyVersion, :digest, :now)
            """)
        .param("decisionId", decisionId)
        .param("rolloutId", rolloutId)
        .param("fromVersion", fromVersion)
        .param("toVersion", toVersion)
        .param("start", timestamp(windowStart))
        .param("end", timestamp(windowEnd))
        .param("requests", requests)
        .param("errors", errors)
        .param("rate", rate)
        .param("threshold", threshold)
        .param("policyVersion", policyVersion)
        .param("digest", digest)
        .param("now", timestamp(now))
        .update();
    for (String instance : targetInstances) {
      jdbc.sql(
              "INSERT INTO rollback_decision_targets (decision_id, gateway_instance_id) VALUES (:decisionId, :instance)")
          .param("decisionId", decisionId)
          .param("instance", instance)
          .update();
    }
    return decisionId;
  }

  public List<PendingDecision> pendingDecisions() {
    return jdbc.sql(
            """
            SELECT decision_id, rollout_id, to_route_version
            FROM rollout_decisions d
            WHERE NOT EXISTS (
              SELECT 1 FROM gateway_convergence_evidence c
              WHERE c.rollout_id = d.rollout_id AND c.target_route_version = d.to_route_version
            )
            """)
        .query(
            (rs, ignored) ->
                new PendingDecision(
                    rs.getObject("decision_id", UUID.class),
                    rs.getObject("rollout_id", UUID.class),
                    rs.getLong("to_route_version")))
        .list();
  }

  public List<String> decisionTargets(UUID decisionId) {
    return jdbc.sql(
            "SELECT gateway_instance_id FROM rollback_decision_targets WHERE decision_id = :id")
        .param("id", decisionId)
        .query(String.class)
        .list();
  }

  public List<String> convergedTargets(UUID decisionId, long routeVersion) {
    return jdbc.sql(
            """
            SELECT t.gateway_instance_id FROM rollback_decision_targets t
            JOIN gateway_route_acks a ON a.gateway_instance_id = t.gateway_instance_id
            WHERE t.decision_id = :id AND a.route_version >= :version
            """)
        .param("id", decisionId)
        .param("version", routeVersion)
        .query(String.class)
        .list();
  }

  public void saveConvergence(
      UUID rolloutId, long routeVersion, String requiredJson, String convergedJson, Instant now) {
    jdbc.sql(
            """
            INSERT INTO gateway_convergence_evidence (
              id, rollout_id, target_route_version, required_instances,
              converged_instances, converged_at
            ) VALUES (:id, :rolloutId, :version, CAST(:required AS jsonb),
                      CAST(:converged AS jsonb), :now)
            """)
        .param("id", UUID.randomUUID())
        .param("rolloutId", rolloutId)
        .param("version", routeVersion)
        .param("required", requiredJson)
        .param("converged", convergedJson)
        .param("now", timestamp(now))
        .update();
  }

  public record PendingDecision(UUID decisionId, UUID rolloutId, long targetRouteVersion) {}

  private static RolloutRow map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    return new RolloutRow(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        RolloutState.valueOf(rs.getString("state")),
        rs.getLong("version"),
        rs.getInt("candidate_ratio"),
        rs.getString("baseline_deployment_id"),
        rs.getString("baseline_base_url"),
        rs.getString("candidate_deployment_id"),
        rs.getString("candidate_base_url"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  public record RolloutRow(
      UUID id,
      String name,
      RolloutState state,
      long version,
      int candidateRatio,
      String baselineDeploymentId,
      String baselineBaseUrl,
      String candidateDeploymentId,
      String candidateBaseUrl,
      Instant updatedAt) {
    RolloutResponse response(long routeVersion) {
      return new RolloutResponse(id, name, state, version, candidateRatio, routeVersion, updatedAt);
    }
  }

  public record IdempotencyRow(String requestHash, int statusCode, String responseBody) {}

  public static final class OptimisticConflictException extends RuntimeException {}
}
