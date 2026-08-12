package io.github.maoyouaa.aegisroute.contracts.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.github.maoyouaa.aegisroute.contracts.events.RouteAppliedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ServingObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ShadowRequestedV1;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSchemaContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final EventSchemaValidator validator = new EventSchemaValidator(objectMapper);

  @Test
  void producerSerializationConformsToPublishedSchema() throws Exception {
    var event =
        new ShadowRequestedV1(
            1,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "fixture-request-1",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            1,
            "candidate-v1",
            Instant.parse("2026-08-12T00:00:00Z"),
            new ChatCompletionRequest(
                "support-assistant",
                List.of(new ChatMessage("user", "Synthetic fixture")),
                false,
                128));

    byte[] json = validator.serializeAndValidate("shadow-requested.schema.json", event);

    assertThat(objectMapper.readTree(json).path("schemaVersion").asInt()).isEqualTo(1);
  }

  @Test
  void everyObservationProducerConformsToItsPublishedSchema() throws Exception {
    UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID sampleId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID rolloutId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    Instant observedAt = Instant.parse("2026-08-12T00:00:01Z");
    var baseline =
        new BaselineObservedV1(
            1,
            eventId,
            sampleId,
            "fixture-request-1",
            rolloutId,
            7,
            "baseline-v1",
            ObservedOutcome.SUCCESS,
            200,
            12,
            observedAt);
    var candidate =
        new CandidateObservedV1(
            1,
            eventId,
            sampleId,
            "fixture-request-1",
            rolloutId,
            7,
            "candidate-v1",
            ObservedOutcome.HTTP_ERROR,
            500,
            15,
            true,
            observedAt);
    var serving =
        new ServingObservedV1(
            1,
            eventId,
            "fixture-request-1",
            rolloutId,
            7,
            "candidate-v1",
            true,
            ObservedOutcome.HTTP_ERROR,
            500,
            16,
            observedAt);
    var routeApplied =
        new RouteAppliedV1(
            1,
            eventId,
            "gateway-1",
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            7,
            "a".repeat(64),
            observedAt);

    assertThat(validator.serializeAndValidate("baseline-observed.schema.json", baseline))
        .isNotEmpty();
    assertThat(validator.serializeAndValidate("candidate-observed.schema.json", candidate))
        .isNotEmpty();
    assertThat(validator.serializeAndValidate("serving-observed.schema.json", serving))
        .isNotEmpty();
    assertThat(validator.serializeAndValidate("route-applied.schema.json", routeApplied))
        .isNotEmpty();
  }

  @Test
  void consumerFixtureValidatesThenDeserializes() throws Exception {
    try (var fixture =
        getClass().getResourceAsStream("/events/v1/fixtures/shadow-requested.json")) {
      var json = validator.readAndValidate("shadow-requested.schema.json", fixture);
      var event = objectMapper.treeToValue(json, ShadowRequestedV1.class);
      assertThat(event.request().messages().getFirst().content()).isEqualTo("Synthetic fixture");
    }
  }

  @Test
  void everyObservationFixtureValidatesThenDeserializesWithExpectedBehavior() throws Exception {
    BaselineObservedV1 baseline = fixture("baseline-observed", BaselineObservedV1.class);
    CandidateObservedV1 candidate = fixture("candidate-observed", CandidateObservedV1.class);
    ServingObservedV1 serving = fixture("serving-observed", ServingObservedV1.class);
    RouteAppliedV1 routeApplied = fixture("route-applied", RouteAppliedV1.class);

    assertThat(baseline.sampleId()).isEqualTo(candidate.sampleId());
    assertThat(candidate.outcome()).isEqualTo(ObservedOutcome.HTTP_ERROR);
    assertThat(serving.candidate()).isTrue();
    assertThat(routeApplied.gatewayInstanceId()).isEqualTo("gateway-1");
  }

  private <T> T fixture(String name, Class<T> type) throws Exception {
    try (var input = getClass().getResourceAsStream("/events/v1/fixtures/" + name + ".json")) {
      var json = validator.readAndValidate(name + ".schema.json", input);
      return objectMapper.treeToValue(json, type);
    }
  }
}
