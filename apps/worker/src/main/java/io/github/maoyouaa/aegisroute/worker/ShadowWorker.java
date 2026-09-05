package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.provider.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ShadowWorker {
  private final ObjectMapper mapper;
  private final EventSchemaValidator validator;
  private final OpenAiProviderFactory providers;
  private final KafkaTemplate<String, byte[]> kafka;
  private final DurableEvidenceStore store;
  private final Duration deadline;
  private final TrustedRouteResolver trusted;

  public ShadowWorker(
      ObjectMapper mapper,
      EventSchemaValidator validator,
      OpenAiProviderFactory providers,
      KafkaTemplate<String, byte[]> kafka,
      DurableEvidenceStore store,
      @Value("${aegis.candidate-deadline:20s}") Duration deadline,
      TrustedRouteResolver trusted) {
    this.mapper = mapper;
    this.validator = validator;
    this.providers = providers;
    this.kafka = kafka;
    this.store = store;
    this.deadline = deadline;
    this.trusted = trusted;
  }

  @KafkaListener(topics = "aegis.shadow-requested.v2", groupId = "aegis-worker-v2")
  public void consume(byte[] payload) {
    ShadowRequestedV2 requested;
    try {
      var tree = mapper.readTree(payload);
      validator.validate("v2/shadow-requested.schema.json", tree);
      requested = mapper.treeToValue(tree, ShadowRequestedV2.class);
    } catch (Exception invalid) {
      store.quarantine("INVALID_SHADOW_CONTRACT", payload);
      return;
    }
    if (!trusted.verify(requested.sample().route())) {
      store.quarantine("UNTRUSTED_SHADOW_ROUTE", payload);
      return;
    }
    store.requested(requested, payload);
  }

  @Scheduled(fixedDelay = 25)
  public void executePending() {
    for (var work : store.pendingExecutions(32)) {
      if (!store.executionOpen(work.key())) continue;
      var requested = work.request();
      RouteSnapshot route = requested.sample().route();
      // Ingress verified the full immutable revision before durable admission.
      // Recovery executes that persisted authority; Control availability is not required again.
      Instant started = Instant.now();
      ObservationV2 result;
      try {
        ChatCompletionRequest input = requested.request();
        var response =
            providers
                .provider(route.candidateBaseUrl())
                .complete(
                    new ChatCompletionRequest(
                        input.model(), input.messages(), false, input.maxTokens()),
                    new ProviderCallContext(requested.sample().requestId(), deadline))
                .block(deadline.plusMillis(250));
        result =
            observation(
                requested,
                started,
                ObservedOutcome.SUCCESS,
                response == null ? 502 : response.statusCode());
      } catch (RuntimeException failed) {
        int status = failed instanceof ProviderException p ? p.statusCode() : 504;
        result =
            observation(
                requested,
                started,
                status == 504 ? ObservedOutcome.TIMEOUT : ObservedOutcome.HTTP_ERROR,
                status);
      }
      byte[] bytes = validator.serializeAndValidate("v2/observation.schema.json", result);
      store.saveResult(work.key(), result, bytes);
    }
  }

  @Scheduled(fixedDelay = 25)
  public void publishResults() {
    for (var publication : store.pendingResults(128)) {
      try {
        kafka
            .send("aegis.observation.v2", publication.key(), publication.payload())
            .get(2, TimeUnit.SECONDS);
        store.resultPublished(publication.key());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception unavailable) {
        return;
      }
    }
  }

  private ObservationV2 observation(
      ShadowRequestedV2 requested, Instant started, ObservedOutcome outcome, int status) {
    return new ObservationV2(
        2,
        UUID.randomUUID(),
        requested.sample(),
        ObservationV2.Kind.SHADOW,
        requested.sample().route().candidateDeploymentId(),
        outcome,
        status,
        Math.max(0, Duration.between(started, Instant.now()).toMillis()),
        Instant.now());
  }
}
