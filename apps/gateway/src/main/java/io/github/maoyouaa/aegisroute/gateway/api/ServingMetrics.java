package io.github.maoyouaa.aegisroute.gateway.api;

import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public final class ServingMetrics {
  private final Map<String, io.micrometer.core.instrument.Timer> timers = new HashMap<>();

  public ServingMetrics(MeterRegistry registry) {
    for (String traffic : List.of("baseline", "candidate"))
      for (var outcome : ObservedOutcome.values()) {
        timers.put(
            traffic + outcome.name(),
            io.micrometer.core.instrument.Timer.builder("aegis_serving")
                .tags("traffic", traffic, "outcome", outcome.name())
                .publishPercentileHistogram()
                .register(registry));
      }
  }

  public void record(boolean candidate, ObservedOutcome outcome, long nanos) {
    timers
        .get((candidate ? "candidate" : "baseline") + outcome.name())
        .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
  }
}
