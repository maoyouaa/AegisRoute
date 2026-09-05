package io.github.maoyouaa.aegisroute.worker;

import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class WorkerMetrics {
  private final DurableEvidenceStore store;
  private final Map<String, Long> values = new ConcurrentHashMap<>();

  public WorkerMetrics(DurableEvidenceStore store, MeterRegistry registry) {
    this.store = store;
    store
        .statistics()
        .forEach(
            (name, value) -> {
              values.put(name, value);
              Gauge.builder("aegis_worker_" + name, values, map -> map.get(name))
                  .register(registry);
            });
  }

  @Scheduled(fixedDelay = 1000)
  public void refresh() {
    values.putAll(store.statistics());
  }
}
