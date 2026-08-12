package io.github.maoyouaa.aegisroute.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ShadowRequestedV1;
import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import io.github.maoyouaa.aegisroute.gateway.shadow.BoundedShadowQueue;
import io.github.maoyouaa.aegisroute.provider.InferenceProvider;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import io.github.maoyouaa.aegisroute.provider.ProviderResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class ChatControllerEventCorrelationTest {
  @Test
  void baselineAndShadowEventsShareTheSameSampleId() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
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

    var queue = new BoundedShadowQueue(8, 64 * 1024, new SimpleMeterRegistry());
    OpenAiProviderFactory factory = Mockito.mock(OpenAiProviderFactory.class);
    InferenceProvider provider = Mockito.mock(InferenceProvider.class);
    when(factory.provider(baselineUrl)).thenReturn(provider);
    when(provider.complete(Mockito.any(), Mockito.any()))
        .thenReturn(Mono.just(new ProviderResponse(200, "baseline", "support-assistant")));
    var controller = new ChatController(snapshots, queue, mapper, factory);

    controller
        .complete(
            new ChatCompletionRequest(
                "support-assistant", List.of(new ChatMessage("user", "synthetic")), false, null),
            "correlation-request")
        .block();

    ShadowRequestedV1 shadow = mapper.readValue(queue.poll().payload(), ShadowRequestedV1.class);
    queue.poll(); // serving-observed
    BaselineObservedV1 baseline =
        mapper.readValue(queue.poll().payload(), BaselineObservedV1.class);
    assertThat(baseline.sampleId()).isEqualTo(shadow.sampleId());
    assertThat(baseline.requestId()).isEqualTo(shadow.requestId());
  }

  @Test
  void telemetrySerializationFailureCannotFailTheBaselineResponse() throws Exception {
    ObjectMapper mapper = Mockito.mock(ObjectMapper.class);
    when(mapper.writeValueAsBytes(Mockito.any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("synthetic failure") {});
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
    var registry = new SimpleMeterRegistry();
    var queue = new BoundedShadowQueue(8, 64 * 1024, registry);
    OpenAiProviderFactory factory = Mockito.mock(OpenAiProviderFactory.class);
    InferenceProvider provider = Mockito.mock(InferenceProvider.class);
    when(factory.provider(baselineUrl)).thenReturn(provider);
    when(provider.complete(Mockito.any(), Mockito.any()))
        .thenReturn(Mono.just(new ProviderResponse(200, "baseline", "support-assistant")));

    var response =
        new ChatController(snapshots, queue, mapper, factory)
            .complete(
                new ChatCompletionRequest(
                    "support-assistant",
                    List.of(new ChatMessage("user", "synthetic")),
                    false,
                    null),
                "serialization-failure")
            .block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(
            registry
                .get("aegis.shadow.dropped")
                .tag("reason", "serialization_error")
                .counter()
                .count())
        .isEqualTo(3);
  }
}
