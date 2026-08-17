package io.github.maoyouaa.aegisroute.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ChatControllerStreamContractTest {
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void successfulStreamEmitsTokensAndOneSuccessObservation() throws Exception {
    Fixture fixture =
        fixture(
            Flux.just(
                new ProviderStreamEvent.Token("hello"), new ProviderStreamEvent.Completed("stop")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-success"))
        .expectNextCount(2)
        .verifyComplete();

    assertThat(servingOutcome(fixture.queue)).isEqualTo(ObservedOutcome.SUCCESS);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void failureBeforeFirstTokenIsObservedAsHttpErrorWithoutRetry() throws Exception {
    Fixture fixture = fixture(Flux.error(new IllegalStateException("before token")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-before-token"))
        .expectErrorMessage("before token")
        .verify();

    assertThat(servingOutcome(fixture.queue)).isEqualTo(ObservedOutcome.HTTP_ERROR);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void failureAfterFirstTokenIsObservedAsStreamErrorWithoutRetry() throws Exception {
    Fixture fixture =
        fixture(
            Flux.concat(
                Flux.just(new ProviderStreamEvent.Token("hello")),
                Flux.error(new IllegalStateException("after token"))));

    StepVerifier.create(fixture.controller.stream(request(), "stream-after-token"))
        .expectNextCount(1)
        .expectErrorMessage("after token")
        .verify();

    assertThat(servingOutcome(fixture.queue)).isEqualTo(ObservedOutcome.STREAM_ERROR);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void clientCancellationPublishesOneCancelledObservation() throws Exception {
    Fixture fixture =
        fixture(Flux.concat(Flux.just(new ProviderStreamEvent.Token("hello")), Flux.never()));

    StepVerifier.create(fixture.controller.stream(request(), "stream-cancel"))
        .expectNextCount(1)
        .thenCancel()
        .verify();

    assertThat(servingOutcome(fixture.queue)).isEqualTo(ObservedOutcome.CANCELLED);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  private ObservedOutcome servingOutcome(BoundedShadowQueue queue) throws Exception {
    queue.poll(); // shadow-requested
    ServingObservedV1 serving = mapper.readValue(queue.poll().payload(), ServingObservedV1.class);
    assertThat(queue.poll()).as("baseline observation follows serving observation").isNotNull();
    assertThat(queue.poll()).as("observation is emitted exactly once").isNull();
    return serving.outcome();
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
    return new Fixture(new ChatController(snapshots, queue, mapper, factory), queue, provider);
  }

  private ChatCompletionRequest request() {
    return new ChatCompletionRequest(
        "support-assistant", List.of(new ChatMessage("user", "synthetic")), true, null);
  }

  private record Fixture(
      ChatController controller, BoundedShadowQueue queue, InferenceProvider provider) {}
}
