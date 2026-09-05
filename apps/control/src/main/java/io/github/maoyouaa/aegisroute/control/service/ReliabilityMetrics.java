package io.github.maoyouaa.aegisroute.control.service;

import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed metric names and bounded slot labels. UUIDs remain in the internal status DTO. */
@Component
public final class ReliabilityMetrics {
  private final ReliabilityStatus status;
  private final Map<String, Double> values = new ConcurrentHashMap<>();

  public ReliabilityMetrics(ReliabilityStatus status, MeterRegistry registry) {
    this.status = status;
    for (String name :
        List.of(
            "available",
            "phase",
            "candidate_ratio",
            "route_version",
            "window_end_seconds",
            "window_route_version",
            "coverage",
            "pairs",
            "unpaired",
            "pending",
            "drops",
            "eligible",
            "shadow_error_ratio",
            "baseline_error_ratio",
            "rollback_kind",
            "rollback_target_version",
            "rollback_targets",
            "rollback_confirmed",
            "rollback_confirmation",
            "gateway_a_confirmed",
            "gateway_b_confirmed",
            "updated_seconds")) {
      values.put(name, Double.NaN);
      Gauge.builder("aegis_control_" + name, values, map -> map.get(name)).register(registry);
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void refresh() {
    ReliabilityStatus.Status s;
    try {
      s = status.read();
    } catch (RuntimeException unavailable) {
      values.replaceAll((key, value) -> key.equals("updated_seconds") ? value : Double.NaN);
      values.put("available", 0d);
      return;
    }
    values.put("available", 1d);
    values.put(
        "phase",
        (double)
            List.of(
                    "DRAFT",
                    "SHADOW",
                    "ELIGIBLE",
                    "CANARY",
                    "FULL",
                    "PAUSED",
                    "BLOCKED",
                    "ROLLBACK_PROPAGATING",
                    "ROLLED_BACK")
                .indexOf(s.phase()));
    values.put("updated_seconds", (double) java.time.Instant.now().getEpochSecond());
    if (s.route() != null) {
      values.put("route_version", (double) s.route().version());
      values.put("candidate_ratio", (double) s.route().candidateRatio());
    }
    var w = s.window();
    for (String key :
        List.of(
            "window_end_seconds",
            "window_route_version",
            "coverage",
            "pairs",
            "unpaired",
            "pending",
            "drops",
            "eligible",
            "shadow_error_ratio",
            "baseline_error_ratio")) values.put(key, Double.NaN);
    if (w != null) {
      var e = w.evidence();
      var r = w.result();
      values.put("window_end_seconds", (double) e.windowEnd().getEpochSecond());
      values.put("window_route_version", (double) e.route().version());
      values.put(
          "coverage",
          r.expectedServing() == 0 || (r.coverage() == 0 && r.observedServing() > 0)
              ? Double.NaN
              : r.coverage());
      values.put("pairs", (double) r.completePairs());
      values.put("unpaired", (double) r.unpairedSamples());
      values.put("pending", (double) r.pendingSamples());
      values.put("drops", (double) r.droppedEvents());
      values.put("eligible", r.eligible() ? 1d : 0d);
      values.put(
          "shadow_error_ratio",
          e.completePairs() == 0 ? Double.NaN : (double) e.shadowErrors() / e.completePairs());
      values.put(
          "baseline_error_ratio",
          e.servingObserved() == e.candidateRequests()
              ? Double.NaN
              : (double) e.baselineErrors() / (e.servingObserved() - e.candidateRequests()));
    }
    var d = s.decision();
    values.put("rollback_kind", d == null ? 0d : d.kind().equals("MANUAL") ? 1d : 2d);
    values.put("rollback_target_version", d == null ? Double.NaN : (double) d.version());
    values.put("rollback_targets", d == null ? Double.NaN : (double) d.required().size());
    values.put("rollback_confirmed", d == null ? Double.NaN : (double) d.confirmed().size());
    values.put(
        "rollback_confirmation",
        d == null || d.required().isEmpty() ? -1d : d.confirmation().equals("CONFIRMED") ? 1d : 0d);
    for (String gateway : List.of("a", "b"))
      values.put(
          "gateway_" + gateway + "_confirmed",
          d == null || !d.required().contains("gateway-" + gateway)
              ? -1d
              : d.confirmed().contains("gateway-" + gateway) ? 1d : 0d);
  }
}
