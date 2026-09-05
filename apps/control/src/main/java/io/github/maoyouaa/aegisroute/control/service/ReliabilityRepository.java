package io.github.maoyouaa.aegisroute.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import io.github.maoyouaa.aegisroute.contracts.events.GatewayWindowReport;
import io.github.maoyouaa.aegisroute.control.api.ApiException;
import io.github.maoyouaa.aegisroute.control.api.WindowReceipt;
import io.github.maoyouaa.aegisroute.control.persistence.CanonicalInput;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReliabilityRepository {
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final CanonicalInput canonical;
  private final RolloutRepository routes;
  private final List<String> configuredInstances;
  private final java.time.Clock clock;

  public ReliabilityRepository(
      JdbcClient jdbc,
      ObjectMapper mapper,
      CanonicalInput canonical,
      RolloutRepository routes,
      @Value("${aegis.expected-gateways:}") String instances,
      java.time.Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.canonical = canonical;
    this.routes = routes;
    this.clock = clock;
    this.configuredInstances =
        Arrays.stream(instances.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
  }

  public List<String> expectedInstances() {
    return configuredInstances.isEmpty()
        ? jdbc.sql(
                "SELECT gateway_instance_id FROM gateway_route_acks ORDER BY gateway_instance_id")
            .query(String.class)
            .list()
        : configuredInstances;
  }

  @Transactional
  public void report(GatewayWindowReport report) {
    RouteSnapshot route =
        routes.route(report.routeId()).orElseThrow(() -> conflict("UNKNOWN_ROUTE"));
    if (route.version() != report.routeVersion()
        || !route.checksum().equals(report.checksum())
        || report.windowEnd().isAfter(clock.instant()))
      throw conflict("INVALID_REPORT_ROUTE_OR_TIME");
    if (!configuredInstances.isEmpty()
        && !configuredInstances.contains(report.gatewayInstanceId())) {
      throw conflict("UNKNOWN_GATEWAY");
    }
    String key =
        report.gatewayInstanceId()
            + "/"
            + report.bootId()
            + "/"
            + report.routeId()
            + "/"
            + report.windowStart();
    routes.lockIdempotencyKey("report/" + key);
    String hash = canonical.hash("gateway-window-v2", report);
    var old =
        jdbc.sql(
                """
        SELECT canonical_hash FROM gateway_window_reports
        WHERE gateway_instance_id=:instance AND boot_id=:boot AND route_id=:route AND window_start=:start
        """)
            .param("instance", report.gatewayInstanceId())
            .param("boot", report.bootId())
            .param("route", report.routeId())
            .param("start", time(report.windowStart()))
            .query(String.class)
            .optional();
    if (old.isPresent()) {
      if (!old.get().equals(hash)) throw conflict("GATEWAY_WINDOW_REUSED");
      return;
    }
    jdbc.sql(
            """
        INSERT INTO gateway_window_reports (gateway_instance_id, boot_id, route_id, window_start,
          window_end, canonical_hash, payload, received_at)
        VALUES (:instance, :boot, :route, :start, :end, :hash, CAST(:payload AS jsonb), :now)
        """)
        .param("instance", report.gatewayInstanceId())
        .param("boot", report.bootId())
        .param("route", report.routeId())
        .param("start", time(report.windowStart()))
        .param("end", time(report.windowEnd()))
        .param("hash", hash)
        .param("payload", json(report))
        .param("now", time(clock.instant()))
        .update();
  }

  public Coverage coverage(EvidenceWindowV2 window) {
    List<String> expected = expectedInstances();
    var reports =
        jdbc
            .sql(
                "SELECT payload FROM gateway_window_reports WHERE route_id=:route AND window_start=:start")
            .param("route", window.route().routeId())
            .param("start", time(window.windowStart()))
            .query(String.class)
            .list()
            .stream()
            .map(value -> read(value, GatewayWindowReport.class))
            .toList();
    boolean known =
        !expected.isEmpty()
            && reports.size() == expected.size()
            && reports.stream().allMatch(GatewayWindowReport::coverageComplete);
    boolean awaiting = false;
    for (String instance : expected) {
      if (reports.stream().noneMatch(report -> report.gatewayInstanceId().equals(instance)))
        awaiting = true;
      if (reports.stream().filter(report -> report.gatewayInstanceId().equals(instance)).count()
          != 1) known = false;
    }
    return new Coverage(
        known,
        awaiting,
        reports.stream().mapToLong(GatewayWindowReport::totalRequests).sum(),
        reports.stream().mapToLong(GatewayWindowReport::candidateRequests).sum(),
        reports.stream().mapToLong(GatewayWindowReport::shadowRequests).sum(),
        reports.stream().mapToLong(GatewayWindowReport::droppedEvents).sum());
  }

  public Optional<WindowReceipt> replay(EvidenceWindowV2 window) {
    var existing =
        jdbc.sql(
                """
        SELECT w.canonical_hash, r.result FROM evidence_windows_v2 w
        JOIN evidence_window_results r USING (window_id) WHERE w.window_id=:id
        """)
            .param("id", window.windowId())
            .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2)))
            .optional();
    if (existing.isEmpty()) return Optional.empty();
    if (!existing.get().hash.equals(canonical.hash("evidence-v2", window)))
      throw conflict("WINDOW_ID_REUSED");
    return Optional.of(read(existing.get().result, WindowReceipt.class));
  }

  public Optional<Previous> previous(UUID routeId) {
    return jdbc.sql(
            """
        SELECT w.window_end, p.consecutive_breaches FROM evidence_windows_v2 w
        JOIN policy_evaluations_v2 p USING (window_id) WHERE w.route_id=:route
        ORDER BY w.window_start DESC LIMIT 1
        """)
        .param("route", routeId)
        .query((rs, row) -> new Previous(rs.getTimestamp(1).toInstant(), rs.getInt(2)))
        .optional();
  }

  public void saveWindow(EvidenceWindowV2 window, Instant now) {
    jdbc.sql(
            """
        INSERT INTO evidence_windows_v2 (window_id, window_version, rollout_id, route_id,
          window_start, window_end, canonical_hash, payload, received_at)
        VALUES (:id, :version, :rollout, :route, :start, :end, :hash, CAST(:payload AS jsonb), :now)
        """)
        .param("id", window.windowId())
        .param("version", window.windowVersion())
        .param("rollout", window.route().rolloutId())
        .param("route", window.route().routeId())
        .param("start", time(window.windowStart()))
        .param("end", time(window.windowEnd()))
        .param("hash", canonical.hash("evidence-v2", window))
        .param("payload", json(window))
        .param("now", time(now))
        .update();
  }

  public void savePolicy(
      UUID window,
      String status,
      boolean eligible,
      boolean breached,
      int consecutive,
      double coverage,
      Instant now) {
    jdbc.sql(
            """
        INSERT INTO policy_evaluations_v2 (window_id, policy_version, status, eligible, breached,
          consecutive_breaches, coverage, created_at)
        VALUES (:id, 2, :status, :eligible, :breached, :streak, :coverage, :now)
        """)
        .param("id", window)
        .param("status", status)
        .param("eligible", eligible)
        .param("breached", breached)
        .param("streak", consecutive)
        .param("coverage", coverage)
        .param("now", time(now))
        .update();
  }

  public void saveResult(WindowReceipt receipt, Instant now) {
    jdbc.sql("INSERT INTO evidence_window_results VALUES (:id, CAST(:result AS jsonb), :now)")
        .param("id", receipt.windowId())
        .param("result", json(receipt))
        .param("now", time(now))
        .update();
  }

  public UUID consumePromotion(RouteSnapshot route, int ratio, String actor, Instant now) {
    var id =
        jdbc.sql(
                """
        SELECT w.window_id FROM evidence_windows_v2 w JOIN policy_evaluations_v2 p USING (window_id)
        WHERE w.route_id=:route AND p.eligible=true AND w.window_end >= :fresh AND w.window_end <= :now
          AND w.window_id=(SELECT window_id FROM evidence_windows_v2 WHERE route_id=:route ORDER BY window_end DESC LIMIT 1)
          AND NOT EXISTS (SELECT 1 FROM promotion_evidence_uses u WHERE u.window_id=w.window_id)
        ORDER BY w.window_end DESC LIMIT 1
        """)
            .param("route", route.routeId())
            .param("fresh", time(now.minusSeconds(120)))
            .param("now", time(now))
            .query(UUID.class)
            .optional();
    if (id.isEmpty()) throw conflict("FRESH_EVIDENCE_REQUIRED");
    jdbc.sql(
            "INSERT INTO promotion_evidence_uses VALUES (:window, :rollout, :route, :ratio, :actor, :now)")
        .param("window", id.get())
        .param("rollout", route.rolloutId())
        .param("route", route.routeId())
        .param("ratio", ratio)
        .param("actor", actor)
        .param("now", time(now))
        .update();
    return id.get();
  }

  public UUID saveRollbackSource(
      UUID rollout, String kind, String actor, String reason, Object payload, Instant now) {
    UUID source = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO rollback_sources VALUES (:id,:rollout,:kind,:actor,:reason,CAST(:payload AS jsonb),:now)")
        .param("id", source)
        .param("rollout", rollout)
        .param("kind", kind)
        .param("actor", actor)
        .param("reason", reason)
        .param("payload", json(payload))
        .param("now", time(now))
        .update();
    return source;
  }

  public void decisionRoute(UUID decision, UUID source, RouteSnapshot target) {
    jdbc.sql(
            "INSERT INTO rollback_route_targets VALUES (:decision,:source,:route,:version,:checksum)")
        .param("decision", decision)
        .param("source", source)
        .param("route", target.routeId())
        .param("version", target.version())
        .param("checksum", target.checksum())
        .update();
  }

  public void lockOwner() {
    routes.lockIdempotencyKey("global-route-owner-v2");
  }

  public boolean hasActiveOwner() {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM rollouts WHERE state NOT IN ('ROLLED_BACK','BLOCKED'))")
        .query(Boolean.class)
        .single();
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Cannot serialize evidence", invalid);
    }
  }

  public <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid stored evidence", invalid);
    }
  }

  public static ApiException conflict(String code) {
    return new ApiException(HttpStatus.CONFLICT, code, code);
  }

  private static java.time.OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private record Stored(String hash, String result) {}

  public record Previous(Instant end, int consecutive) {}

  public record Coverage(
      boolean known, boolean awaiting, long total, long candidate, long shadow, long dropped) {}
}
