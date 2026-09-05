package io.github.maoyouaa.aegisroute.gateway.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.api.*;
import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import io.github.maoyouaa.aegisroute.gateway.shadow.BoundedShadowQueue;
import io.github.maoyouaa.aegisroute.provider.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

class ChatControllerEventCorrelationTest {
  final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final ChatCompletionRequest request =
      new ChatCompletionRequest(
          "synthetic", List.of(new ChatMessage("user", "synthetic")), false, null);

  @Test
  void baselineAndShadowEventsShareFullIdentityAndValidateV2Schemas() throws Exception {
    var fixture = fixture(RolloutState.SHADOW, 0, mapper);
    fixture.controller.complete(request, "correlation-request").block();
    var shadowBytes = fixture.queue.poll().payload();
    var baselineBytes = fixture.queue.poll().payload();
    var validator = new EventSchemaValidator(mapper);
    validator.validate("v2/shadow-requested.schema.json", mapper.readTree(shadowBytes));
    validator.validate("v2/observation.schema.json", mapper.readTree(baselineBytes));
    var shadow = mapper.readValue(shadowBytes, ShadowRequestedV2.class);
    var baseline = mapper.readValue(baselineBytes, ObservationV2.class);
    assertThat(baseline.sample()).isEqualTo(shadow.sample());
    assertThat(baseline.deploymentId()).isEqualTo("baseline-v1");
    assertThat(fixture.queue.poll()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = RolloutState.class,
      names = {"DRAFT", "PAUSED", "ROLLED_BACK", "ROLLBACK_PROPAGATING", "BLOCKED", "FULL"})
  void inactiveOrCandidateServedRequestsNeverEnqueueShadow(RolloutState state) throws Exception {
    var fixture = fixture(state, state == RolloutState.FULL ? 100 : 0, mapper);
    assertThat(
            fixture
                .controller
                .complete(request, "lifecycle-request")
                .block()
                .getStatusCode()
                .value())
        .isEqualTo(200);
    var observed = mapper.readValue(fixture.queue.poll().payload(), ObservationV2.class);
    assertThat(observed.sample().shadowSelected()).isFalse();
    assertThat(observed.kind())
        .isEqualTo(
            state == RolloutState.FULL ? ObservationV2.Kind.CANARY : ObservationV2.Kind.BASELINE);
    assertThat(fixture.queue.poll()).isNull();
    verify(fixture.provider, times(1)).complete(any(), any());
  }

  @Test
  void telemetrySerializationFailureCannotFailTheBaselineResponse() throws Exception {
    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsBytes(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("synthetic failure") {});
    var fixture = fixture(RolloutState.SHADOW, 0, broken);
    assertThat(
            fixture
                .controller
                .complete(request, "serialization-failure")
                .block()
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            fixture
                .registry
                .get("aegis.shadow.dropped")
                .tag("reason", "serialization_error")
                .counter()
                .count())
        .isEqualTo(2);
  }

  private Fixture fixture(RolloutState state, int ratio, ObjectMapper eventMapper) {
    var snapshots = new RouteSnapshotStore();
    int shadow = state == RolloutState.SHADOW || state == RolloutState.CANARY ? 100 : 0;
    snapshots.apply(
        RouteSnapshot.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "baseline-v1",
            "http://baseline:8080",
            "candidate-v1",
            "http://candidate:8080",
            ratio,
            Instant.now(),
            state,
            shadow));
    var registry = new SimpleMeterRegistry();
    var queue = new BoundedShadowQueue(16, 65536, registry);
    var factory = mock(OpenAiProviderFactory.class);
    var provider = mock(InferenceProvider.class);
    when(factory.provider(any())).thenReturn(provider);
    when(provider.complete(any(), any()))
        .thenReturn(Mono.just(new ProviderResponse(200, "synthetic response", "synthetic")));
    return new Fixture(
        new ChatController(snapshots, queue, eventMapper, factory), queue, provider, registry);
  }

  private record Fixture(
      ChatController controller,
      BoundedShadowQueue queue,
      InferenceProvider provider,
      SimpleMeterRegistry registry) {}
}
