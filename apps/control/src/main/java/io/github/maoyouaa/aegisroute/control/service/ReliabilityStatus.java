package io.github.maoyouaa.aegisroute.control.service;

import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import io.github.maoyouaa.aegisroute.control.api.WindowReceipt;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of authoritative persisted evidence; never a second policy engine. */
@Service
public class ReliabilityStatus {
  private final RolloutRepository routes;
  private final ReliabilityRepository reliability;
  private final JdbcClient jdbc;

  public ReliabilityStatus(
      RolloutRepository routes, ReliabilityRepository reliability, JdbcClient jdbc) {
    this.routes = routes;
    this.reliability = reliability;
    this.jdbc = jdbc;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Status read() {
    var route = routes.latestRoute().orElse(null);
    if (route == null) return new Status("UNKNOWN", 0, null, null, null);
    var rollout = routes.find(route.rolloutId()).orElseThrow();
    var window =
        jdbc.sql(
                """
        SELECT w.payload,r.result FROM evidence_windows_v2 w JOIN evidence_window_results r USING(window_id)
        WHERE w.rollout_id=:id ORDER BY w.window_end DESC,w.received_at DESC LIMIT 1
        """)
            .param("id", route.rolloutId())
            .query(
                (rs, row) ->
                    new Window(
                        reliability.read(rs.getString(1), EvidenceWindowV2.class),
                        reliability.read(rs.getString(2), WindowReceipt.class)))
            .optional()
            .orElse(null);
    var decision =
        jdbc.sql(
                """
        SELECT d.decision_id,t.route_id,t.route_version,t.checksum,s.kind,d.created_at
        FROM rollout_decisions d JOIN rollback_route_targets t USING(decision_id)
        JOIN rollback_sources s USING(source_id) WHERE d.rollout_id=:id ORDER BY d.created_at DESC LIMIT 1
        """)
            .param("id", route.rolloutId())
            .query(
                (rs, row) -> {
                  UUID id = rs.getObject(1, UUID.class);
                  long version = rs.getLong(3);
                  var required = routes.decisionTargets(id);
                  var confirmed = routes.convergedTargets(id, version);
                  return new Decision(
                      id,
                      rs.getObject(2, UUID.class),
                      version,
                      rs.getString(4),
                      rs.getString(5),
                      rs.getTimestamp(6).toInstant(),
                      required,
                      confirmed,
                      required.isEmpty()
                          ? "NO_TARGETS"
                          : confirmed.containsAll(required) ? "CONFIRMED" : "PENDING");
                })
            .optional()
            .orElse(null);
    return new Status(rollout.state().name(), rollout.version(), route, window, decision);
  }

  public record Status(
      String phase, long rolloutVersion, RouteSnapshot route, Window window, Decision decision) {}

  public record Window(EvidenceWindowV2 evidence, WindowReceipt result) {}

  public record Decision(
      UUID id,
      UUID routeId,
      long version,
      String checksum,
      String kind,
      Instant createdAt,
      List<String> required,
      List<String> confirmed,
      String confirmation) {}
}
