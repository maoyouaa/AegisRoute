package io.github.maoyouaa.aegisroute.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.github.maoyouaa.aegisroute.contracts.events.ServingObservedV1;
import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import io.github.maoyouaa.aegisroute.gateway.shadow.BoundedShadowQueue;
import io.github.maoyouaa.aegisroute.provider.InferenceProvider;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class ChatControllerHttpStreamContractTest {
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void exposesPreTokenProvider429WithoutRetry() throws Exception {
    assertPreTokenProviderStatus(429);
  }

  @Test
  void exposesPreTokenProvider500WithoutRetry() throws Exception {
    assertPreTokenProviderStatus(500);
  }

  @Test
  void exposesPreTokenConnectionFailureAs502WithoutRetry() throws Exception {
    assertPreTokenFailure(
        new ConnectException("synthetic connection detail"), 502, ObservedOutcome.HTTP_ERROR);
  }

  @Test
  void exposesPreTokenDeadlineAs504WithoutRetry() throws Exception {
    assertPreTokenFailure(
        new TimeoutException("synthetic timeout detail"), 504, ObservedOutcome.TIMEOUT);
  }

  private void assertPreTokenProviderStatus(int statusCode) throws Exception {
    assertPreTokenFailure(
        new ProviderException(statusCode, false, "synthetic detail"),
        statusCode,
        ObservedOutcome.HTTP_ERROR);
  }

  private void assertPreTokenFailure(
      Throwable failure, int statusCode, ObservedOutcome expectedOutcome) throws Exception {
    Fixture fixture = fixture(Flux.error(failure));

    fixture
        .client
        .post()
        .uri("/v1/chat/completions")
        .header("X-Request-Id", "http-stream-" + statusCode)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(request())
        .exchange()
        .expectStatus()
        .isEqualTo(statusCode)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody(ApiError.class)
        .value(
            error -> {
              assertThat(error.error().code()).isEqualTo("PROVIDER_FAILURE");
              assertThat(error.error().message()).isEqualTo("Upstream provider request failed");
              assertThat(error.error().message()).doesNotContain("synthetic", "detail");
            });

    fixture.queue.poll(); // shadow-requested
    ServingObservedV1 serving =
        mapper.readValue(fixture.queue.poll().payload(), ServingObservedV1.class);
    assertThat(serving.outcome()).isEqualTo(expectedOutcome);
    assertThat(serving.statusCode()).isEqualTo(statusCode);
    assertThat(fixture.queue.poll())
        .as("baseline observation follows serving observation")
        .isNotNull();
    assertThat(fixture.queue.poll()).as("observation is emitted exactly once").isNull();
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  private Fixture fixture(Flux<ProviderStreamEvent> stream) {
    var snapshots = new RouteSnapshotStore();
    UUID routeId = UUID.randomUUID();
    UUID rolloutId = UUID.randomUUID();
    String baselineUrl = "http://baseline:8080";
    String candidateUrl = "http://candidate:8080";
    String checksum =
        RouteChecksum.calculate(
            routeId, rolloutId, 1, "baseline-v1", baselineUrl, "candidate-v1", candidateUrl, 0);
    snapshots.apply(
        new RouteSnapshot(
            routeId,
            rolloutId,
            1,
            "baseline-v1",
            baselineUrl,
            "candidate-v1",
            candidateUrl,
            0,
            checksum,
            Instant.parse("2026-08-12T00:00:00Z")));
    var queue = new BoundedShadowQueue(16, 64 * 1024, new SimpleMeterRegistry());
    OpenAiProviderFactory factory = Mockito.mock(OpenAiProviderFactory.class);
    InferenceProvider provider = Mockito.mock(InferenceProvider.class);
    when(factory.provider(baselineUrl)).thenReturn(provider);
    when(provider.stream(Mockito.any(), Mockito.any())).thenReturn(stream);
    var controller = new ChatController(snapshots, queue, mapper, factory);
    WebTestClient client =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GatewayExceptionHandler())
            .build();
    return new Fixture(client, queue, provider);
  }

  private ChatCompletionRequest request() {
    return new ChatCompletionRequest(
        "support-assistant", List.of(new ChatMessage("user", "synthetic")), true, null);
  }

  private record Fixture(
      WebTestClient client, BoundedShadowQueue queue, InferenceProvider provider) {}
}
