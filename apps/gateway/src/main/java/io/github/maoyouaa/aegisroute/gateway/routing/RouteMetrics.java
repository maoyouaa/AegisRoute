package io.github.maoyouaa.aegisroute.gateway.routing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class RouteMetrics {
  public RouteMetrics(RouteSnapshotStore store, MeterRegistry registry) {
    Gauge.builder(
            "aegis.route.snapshot.available", store, value -> value.current().isPresent() ? 1 : 0)
        .register(registry);
    Gauge.builder(
            "aegis.route.snapshot.age.seconds", store, value -> value.age().toMillis() / 1000.0)
        .register(registry);
    Gauge.builder("aegis.route.version", store, RouteSnapshotStore::version).register(registry);
  }
}
