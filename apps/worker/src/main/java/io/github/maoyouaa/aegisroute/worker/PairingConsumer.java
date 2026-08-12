package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public final class PairingConsumer {
  private final ObjectMapper objectMapper;
  private final EventSchemaValidator validator;
  private final ResultPairingStore pairs;
  private final ShadowEligibilityEvaluator eligibility;
  private final WebClient control;
  private final LongAdder completedPairs = new LongAdder();

  public PairingConsumer(
      ObjectMapper objectMapper,
      EventSchemaValidator validator,
      ResultPairingStore pairs,
      ShadowEligibilityEvaluator eligibility,
      WebClient.Builder webClientBuilder,
      @Value("${aegis.control-base-url:http://control:8081}") String controlBaseUrl) {
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.pairs = pairs;
    this.eligibility = eligibility;
    this.control = webClientBuilder.baseUrl(controlBaseUrl).build();
  }

  @KafkaListener(topics = "aegis.baseline-observed.v1", groupId = "aegis-pairing-v1")
  public void baseline(byte[] payload) throws Exception {
    var tree = objectMapper.readTree(payload);
    validator.validate("baseline-observed.schema.json", tree);
    pairs
        .baseline(objectMapper.treeToValue(tree, BaselineObservedV1.class))
        .ifPresent(this::complete);
  }

  @KafkaListener(topics = "aegis.candidate-observed.v1", groupId = "aegis-pairing-v1")
  public void candidate(byte[] payload) throws Exception {
    var tree = objectMapper.readTree(payload);
    validator.validate("candidate-observed.schema.json", tree);
    pairs
        .candidate(objectMapper.treeToValue(tree, CandidateObservedV1.class))
        .ifPresent(this::complete);
  }

  @Scheduled(fixedDelay = 60000)
  public void expire() {
    pairs.expire();
  }

  @Scheduled(fixedDelay = 1000)
  public void retryEligibilityPublication() {
    eligibility.pendingDecisions().forEach(this::publishEligibility);
  }

  public long completedPairs() {
    return completedPairs.sum();
  }

  private void complete(ResultPairingStore.Pair pair) {
    completedPairs.increment();
    eligibility.record(pair).ifPresent(this::publishEligibility);
  }

  private void publishEligibility(ShadowEligibilityEvaluator.Decision decision) {
    try {
      control
          .post()
          .uri("/internal/v1/rollouts/{id}/eligibility", decision.rolloutId())
          .bodyValue(java.util.Map.of("eligible", decision.eligible(), "reason", decision.reason()))
          .retrieve()
          .toBodilessEntity()
          .block();
      eligibility.markPublished(decision.rolloutId());
    } catch (RuntimeException unavailable) {
      // Evaluation publication is outside the baseline critical path; the scheduled task retries
      // it.
    }
  }
}
