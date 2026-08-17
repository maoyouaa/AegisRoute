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
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  void http429BeforeFirstTokenPreservesProviderStatusWithoutRetry() throws Exception {
    Fixture fixture = fixture(Flux.error(new ProviderException(429, false, "limited")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-429"))
        .expectError(ProviderException.class)
        .verify();

    ServingObservedV1 serving = servingObservation(fixture.queue);
    assertThat(serving.outcome()).isEqualTo(ObservedOutcome.HTTP_ERROR);
    assertThat(serving.statusCode()).isEqualTo(429);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void http500BeforeFirstTokenPreservesProviderStatusWithoutRetry() throws Exception {
    Fixture fixture = fixture(Flux.error(new ProviderException(500, false, "failed")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-500"))
        .expectError(ProviderException.class)
        .verify();

    ServingObservedV1 serving = servingObservation(fixture.queue);
    assertThat(serving.outcome()).isEqualTo(ObservedOutcome.HTTP_ERROR);
    assertThat(serving.statusCode()).isEqualTo(500);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void responseStartedWithoutTokenIsObservedAsStreamErrorWithoutRetry() throws Exception {
    Fixture fixture = fixture(Flux.error(new ProviderException(502, true, "headers started")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-response-started"))
        .expectError(ProviderException.class)
        .verify();

    ServingObservedV1 serving = servingObservation(fixture.queue);
    assertThat(serving.outcome()).isEqualTo(ObservedOutcome.STREAM_ERROR);
    assertThat(serving.statusCode()).isEqualTo(502);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void connectionFailureBeforeFirstTokenIsObservedWithoutRetry() throws Exception {
    Fixture fixture = fixture(Flux.error(new ConnectException("refused")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-connect"))
        .expectError(ConnectException.class)
        .verify();

    ServingObservedV1 serving = servingObservation(fixture.queue);
    assertThat(serving.outcome()).isEqualTo(ObservedOutcome.HTTP_ERROR);
    assertThat(serving.statusCode()).isEqualTo(502);
    verify(fixture.provider, times(1)).stream(Mockito.any(), Mockito.any());
  }

  @Test
  void totalDeadlineIsObservedAsTimeoutAndIsNotReset() throws Exception {
    Fixture fixture = fixture(Flux.error(new TimeoutException("deadline")));

    StepVerifier.create(fixture.controller.stream(request(), "stream-deadline"))
        .expectError(TimeoutException.class)
        .verify();

    ServingObservedV1 serving = servingObservation(fixture.queue);
    assertThat(serving.outcome()).isEqualTo(ObservedOutcome.TIMEOUT);
    assertThat(serving.statusCode()).isEqualTo(504);
    ArgumentCaptor<ProviderCallContext> context =
        ArgumentCaptor.forClass(ProviderCallContext.class);
    verify(fixture.provider, times(1)).stream(Mockito.any(), context.capture());
    assertThat(context.getValue().deadline()).isEqualTo(Duration.ofSeconds(30));
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
    return servingObservation(queue).outcome();
  }

  private ServingObservedV1 servingObservation(BoundedShadowQueue queue) throws Exception {
    queue.poll(); // shadow-requested
    ServingObservedV1 serving = mapper.readValue(queue.poll().payload(), ServingObservedV1.class);
    assertThat(queue.poll()).as("baseline observation follows serving observation").isNotNull();
    assertThat(queue.poll()).as("observation is emitted exactly once").isNull();
    return serving;
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
