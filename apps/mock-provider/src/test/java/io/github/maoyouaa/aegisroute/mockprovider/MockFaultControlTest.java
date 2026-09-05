package io.github.maoyouaa.aegisroute.mockprovider;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class MockFaultControlTest {
  @Test
  void controlsAreAbsentByDefaultAndFaultPayloadIsBounded() {
    var controller =
        new MockProviderController(
            new MockProviderProperties("synthetic", 0, Duration.ZERO, "synthetic", false));
    var client = WebTestClient.bindToController(controller).build();
    client.get().uri("/internal/stats").exchange().expectStatus().isNotFound();
    client
        .post()
        .uri("/internal/faults")
        .bodyValue(new MockProviderController.Fault("http-500", 0, 0))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThatThrownBy(() -> new MockProviderController.Fault("arbitrary-command", 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MockProviderController.Fault("normal", 0, 30001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitSyntheticModeEnablesControlsWithoutAffectingOtherMockInstances() {
    var enabled =
        new MockProviderController(
            new MockProviderProperties("synthetic", 0, Duration.ZERO, "synthetic", true));
    var other =
        new MockProviderController(
            new MockProviderProperties("other", 0, Duration.ZERO, "synthetic", true));
    WebTestClient.bindToController(enabled)
        .build()
        .post()
        .uri("/internal/faults")
        .bodyValue(new MockProviderController.Fault("timeout", 0, 0))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(((MockProviderController.Fault) enabled.stats().get("fault")).mode())
        .isEqualTo("timeout");
    assertThat(((MockProviderController.Fault) other.stats().get("fault")).mode())
        .isEqualTo("normal");
  }
}
