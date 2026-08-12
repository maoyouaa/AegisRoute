package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.github.maoyouaa.aegisroute.contracts.events.ShadowRequestedV1;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ShadowWorker {
  private final ObjectMapper objectMapper;
  private final EventSchemaValidator validator;
  private final OpenAiProviderFactory providers;
  private final KafkaTemplate<String, byte[]> kafka;
  private final String candidateBaseUrl;

  public ShadowWorker(
      ObjectMapper objectMapper,
      EventSchemaValidator validator,
      OpenAiProviderFactory providers,
      KafkaTemplate<String, byte[]> kafka,
      @Value("${aegis.candidate-base-url:http://candidate:8080}") String candidateBaseUrl) {
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.providers = providers;
    this.kafka = kafka;
    this.candidateBaseUrl = candidateBaseUrl;
  }

  @KafkaListener(topics = "aegis.shadow-requested.v1", groupId = "aegis-worker-v1")
  public void consume(byte[] payload) throws Exception {
    var tree = objectMapper.readTree(payload);
    validator.validate("shadow-requested.schema.json", tree);
    ShadowRequestedV1 requested = objectMapper.treeToValue(tree, ShadowRequestedV1.class);
    Instant started = Instant.now();
    providers
        .provider(candidateBaseUrl)
        .complete(
            requested.request(),
            new ProviderCallContext(requested.requestId(), Duration.ofSeconds(20)))
        .map(
            response ->
                observation(requested, started, ObservedOutcome.SUCCESS, response.statusCode()))
        .onErrorReturn(observation(requested, started, ObservedOutcome.HTTP_ERROR, 500))
        .map(event -> validator.serializeAndValidate("candidate-observed.schema.json", event))
        .doOnNext(
            event ->
                kafka.send("aegis.candidate-observed.v1", requested.sampleId().toString(), event))
        .block();
  }

  private CandidateObservedV1 observation(
      ShadowRequestedV1 requested, Instant started, ObservedOutcome outcome, int statusCode) {
    return new CandidateObservedV1(
        1,
        UUID.randomUUID(),
        requested.sampleId(),
        requested.requestId(),
        requested.rolloutId(),
        requested.routeVersion(),
        requested.candidateDeploymentId(),
        outcome,
        statusCode,
        Math.max(0, Duration.between(started, Instant.now()).toMillis()),
        true,
        Instant.now());
  }
}
