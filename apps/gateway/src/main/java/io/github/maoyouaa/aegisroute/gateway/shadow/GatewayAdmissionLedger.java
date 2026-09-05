package io.github.maoyouaa.aegisroute.gateway.shadow;

import io.github.maoyouaa.aegisroute.contracts.events.GatewayWindowReport;
import io.github.maoyouaa.aegisroute.contracts.events.SampleIdentity;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Admission counters only on serving threads; HTTP publication runs on the scheduler. */
@Component
public final class GatewayAdmissionLedger {
  private final String instance;
  private final UUID bootId = UUID.randomUUID();
  private final Instant bootStartedAt;
  private final java.time.Clock clock;
  private final int capacity;
  private final RouteSnapshotStore snapshots;
  private final WebClient control;
  private final Duration grace;
  private final ConcurrentHashMap<UUID, Counts> buckets = new ConcurrentHashMap<>();
  private final AtomicInteger slots = new AtomicInteger();
  private final AtomicLong unavailable = new AtomicLong();
  private final java.util.concurrent.atomic.AtomicBoolean accountingLost =
      new java.util.concurrent.atomic.AtomicBoolean();

  @org.springframework.beans.factory.annotation.Autowired
  public GatewayAdmissionLedger(
      @Value("${aegis.gateway-instance-id:${HOSTNAME:gateway-local}}") String instance,
      RouteSnapshotStore snapshots,
      WebClient.Builder builder,
      @Value("${aegis.control-base-url:http://control:8081}") String controlUrl,
      @Value("${aegis.evidence-grace:35s}") Duration grace) {
    this(instance, snapshots, builder, controlUrl, grace, java.time.Clock.systemUTC(), 4096);
  }

  GatewayAdmissionLedger(
      String instance,
      RouteSnapshotStore snapshots,
      WebClient.Builder builder,
      String controlUrl,
      Duration grace,
      java.time.Clock clock,
      int capacity) {
    this.clock = clock;
    this.bootStartedAt = clock.instant();
    this.capacity = capacity;
    this.instance = instance;
    this.snapshots = snapshots;
    this.control = builder.baseUrl(controlUrl).build();
    this.grace = grace;
  }

  public SampleIdentity admit(RouteSnapshot route, String requestId, boolean candidate) {
    Instant now = clock.instant();
    var sample =
        new SampleIdentity(
            instance,
            bootId,
            UUID.randomUUID(),
            requestId,
            route,
            now,
            route.shadows(requestId, candidate));
    Counts counts = bucket(route, sample.windowStart());
    if (counts != null) {
      counts.total.incrementAndGet();
      if (candidate) counts.candidate.incrementAndGet();
      if (sample.shadowSelected()) counts.shadow.incrementAndGet();
    }
    return sample;
  }

  public void dropped(SampleIdentity sample) {
    Counts counts = buckets.get(sample.windowId());
    if (counts != null) counts.drops.incrementAndGet();
  }

  public long pendingReports() {
    return buckets.size();
  }

  public long unavailableReports() {
    return unavailable.get();
  }

  private Counts bucket(RouteSnapshot route, Instant start) {
    UUID id = SampleIdentity.windowId(route.routeId(), start);
    Counts found = buckets.get(id);
    if (found != null) return found;
    if (slots.incrementAndGet() > capacity) {
      slots.decrementAndGet();
      unavailable.incrementAndGet();
      accountingLost.set(true);
      return null;
    }
    var created = new Counts(route, start);
    found = buckets.putIfAbsent(id, created);
    if (found != null) slots.decrementAndGet();
    return found == null ? created : found;
  }

  @Scheduled(fixedDelay = 1000)
  public void publish() {
    Instant now = clock.instant();
    snapshots.current().ifPresent(route -> bucket(route, SampleIdentity.bucket(now)));
    for (var entry :
        buckets.entrySet().stream()
            .sorted(Comparator.comparing(value -> value.getValue().start))
            .limit(128)
            .toList()) {
      Counts counts = entry.getValue();
      if (now.isBefore(counts.start.plusSeconds(5).plus(grace))) continue;
      if (counts.sealed == null) {
        boolean complete = !accountingLost.get() && !counts.start.isBefore(bootStartedAt);
        String reason =
            accountingLost.get() ? "ADMISSION_LOSS" : complete ? "COMPLETE" : "BOOT_PARTIAL";
        counts.sealed =
            new GatewayWindowReport(
                2,
                instance,
                bootId,
                counts.route.routeId(),
                counts.route.version(),
                counts.route.checksum(),
                counts.start,
                counts.start.plusSeconds(5),
                counts.total.get(),
                counts.candidate.get(),
                counts.shadow.get(),
                counts.drops.get(),
                complete,
                reason);
      }
      try {
        control
            .post()
            .uri("/internal/v2/gateway-windows")
            .bodyValue(counts.sealed)
            .retrieve()
            .toBodilessEntity()
            .block(Duration.ofMillis(750));
        if (buckets.remove(entry.getKey(), counts)) slots.decrementAndGet();
      } catch (RuntimeException unavailableControl) {
        // Exact sealed report is retained. Missing report means unknown coverage at Control.
        break;
      }
    }
  }

  private static final class Counts {
    final RouteSnapshot route;
    final Instant start;
    final AtomicLong total = new AtomicLong();
    final AtomicLong candidate = new AtomicLong();
    final AtomicLong shadow = new AtomicLong();
    final AtomicLong drops = new AtomicLong();
    GatewayWindowReport sealed;

    Counts(RouteSnapshot route, Instant start) {
      this.route = route;
      this.start = start;
    }
  }
}
