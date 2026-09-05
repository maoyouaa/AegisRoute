package io.github.maoyouaa.aegisroute.control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ReliabilityMetricsTest {
  @Test
  void sourceFailureClearsHealthyProjectionAndRetainsLastSuccessTime() {
    var source = mock(ReliabilityStatus.class);
    var registry = new SimpleMeterRegistry();
    when(source.read())
        .thenReturn(new ReliabilityStatus.Status("DRAFT", 1, null, null, null))
        .thenThrow(new IllegalStateException("synthetic database outage"));
    var metrics = new ReliabilityMetrics(source, registry);
    metrics.refresh();
    assertThat(registry.get("aegis_control_phase").gauge().value()).isZero();
    double updated = registry.get("aegis_control_updated_seconds").gauge().value();
    metrics.refresh();
    assertThat(registry.get("aegis_control_phase").gauge().value()).isNaN();
    assertThat(registry.get("aegis_control_available").gauge().value()).isZero();
    assertThat(registry.get("aegis_control_updated_seconds").gauge().value()).isEqualTo(updated);
  }
}
