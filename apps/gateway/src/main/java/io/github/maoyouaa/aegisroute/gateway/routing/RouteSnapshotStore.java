package io.github.maoyouaa.aegisroute.gateway.routing;

import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public final class RouteSnapshotStore {
  private final AtomicReference<AppliedSnapshot> current = new AtomicReference<>();
  private final Clock clock;

  public RouteSnapshotStore() {
    this(Clock.systemUTC());
  }

  RouteSnapshotStore(Clock clock) {
    this.clock = clock;
  }

  public boolean apply(RouteSnapshot candidate) {
    String calculated =
        RouteChecksum.calculate(
            candidate.routeId(),
            candidate.rolloutId(),
            candidate.version(),
            candidate.baselineDeploymentId(),
            candidate.baselineBaseUrl(),
            candidate.candidateDeploymentId(),
            candidate.candidateBaseUrl(),
            candidate.candidateRatio());
    if (!calculated.equals(candidate.checksum())) return false;
    while (true) {
      AppliedSnapshot existing = current.get();
      if (existing != null && candidate.version() <= existing.snapshot().version()) return false;
      if (current.compareAndSet(existing, new AppliedSnapshot(candidate, clock.instant())))
        return true;
    }
  }

  public Optional<RouteSnapshot> current() {
    return Optional.ofNullable(current.get()).map(AppliedSnapshot::snapshot);
  }

  public long version() {
    return current().map(RouteSnapshot::version).orElse(0L);
  }

  public Duration age() {
    AppliedSnapshot applied = current.get();
    return applied == null ? Duration.ZERO : Duration.between(applied.appliedAt(), clock.instant());
  }

  public Instant appliedAt() {
    AppliedSnapshot applied = current.get();
    return applied == null ? null : applied.appliedAt();
  }

  private record AppliedSnapshot(RouteSnapshot snapshot, Instant appliedAt) {}
}
