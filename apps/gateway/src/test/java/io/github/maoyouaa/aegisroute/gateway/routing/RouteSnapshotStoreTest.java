package io.github.maoyouaa.aegisroute.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteSnapshotStoreTest {
  private static final UUID ROUTE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ROLLOUT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void acceptsOnlyChecksumValidMonotonicSnapshots() {
    var store =
        new RouteSnapshotStore(Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
    RouteSnapshot first = snapshot(1, checksum(1), 0);

    assertThat(store.apply(first)).isTrue();
    assertThat(store.apply(snapshot(1, checksum(1), 0))).isFalse();
    assertThat(store.apply(snapshot(2, "0".repeat(64), 10))).isFalse();
    assertThat(store.version()).isEqualTo(1);
  }

  private RouteSnapshot snapshot(long version, String checksum, int ratio) {
    return new RouteSnapshot(
        ROUTE_ID,
        ROLLOUT_ID,
        version,
        "baseline-v1",
        "http://baseline:8080",
        "candidate-v1",
        "http://candidate:8080",
        ratio,
        checksum,
        Instant.parse("2026-08-12T00:00:00Z"));
  }

  private String checksum(long version) {
    return RouteChecksum.calculate(
        ROUTE_ID,
        ROLLOUT_ID,
        version,
        "baseline-v1",
        "http://baseline:8080",
        "candidate-v1",
        "http://candidate:8080",
        0);
  }
}
