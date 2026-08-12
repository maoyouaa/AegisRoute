package io.github.maoyouaa.aegisroute.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aegis.control-base-url=http://127.0.0.1:1",
      "aegis.snapshot-poll-interval=3600000",
      "spring.kafka.bootstrap-servers=127.0.0.1:1"
    })
class GatewayApplicationContextTest {
  @Autowired ProducerFactory<String, byte[]> producerFactory;
  @LocalServerPort int port;

  @Test
  void loadsCompleteApplicationContext() {}

  @Test
  void eventProducerUsesByteArraySerialization() {
    assertThat(producerFactory.getConfigurationProperties().get("value.serializer"))
        .isEqualTo(org.apache.kafka.common.serialization.ByteArraySerializer.class);
  }

  @Test
  void noSnapshotKeepsLivenessUpButReturnsStructuredUnavailability() {
    WebTestClient webTestClient =
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    webTestClient.get().uri("/health/live").exchange().expectStatus().isOk();
    webTestClient.get().uri("/health/ready").exchange().expectStatus().isEqualTo(503);
    webTestClient
        .post()
        .uri("/v1/chat/completions")
        .header("Content-Type", "application/json")
        .bodyValue(
            """
            {"model":"support-assistant","stream":false,
             "messages":[{"role":"user","content":"synthetic"}]}
            """)
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("ROUTE_SNAPSHOT_UNAVAILABLE");
  }
}
