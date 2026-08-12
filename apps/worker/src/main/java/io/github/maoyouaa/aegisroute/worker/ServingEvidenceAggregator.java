package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.github.maoyouaa.aegisroute.contracts.events.ServingObservedV1;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public final class ServingEvidenceAggregator {
  private final ObjectMapper objectMapper;
  private final EventSchemaValidator validator;
  private final WebClient control;
  private final ConcurrentHashMap<UUID, Counts> counts = new ConcurrentHashMap<>();
  private volatile Instant windowStart = Instant.now();

  public ServingEvidenceAggregator(
      ObjectMapper objectMapper,
      EventSchemaValidator validator,
      WebClient.Builder builder,
      @Value("${aegis.control-base-url:http://control:8081}") String controlBaseUrl) {
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.control = builder.baseUrl(controlBaseUrl).build();
  }

  @KafkaListener(topics = "aegis.serving-observed.v1", groupId = "aegis-serving-evidence-v1")
  public void consume(byte[] payload) throws Exception {
    var tree = objectMapper.readTree(payload);
    validator.validate("serving-observed.schema.json", tree);
    ServingObservedV1 observed = objectMapper.treeToValue(tree, ServingObservedV1.class);
    if (!observed.candidate()) return;
    Counts rolloutCounts = counts.computeIfAbsent(observed.rolloutId(), ignored -> new Counts());
    rolloutCounts.requests.increment();
    if (observed.outcome() != ObservedOutcome.SUCCESS || observed.statusCode() >= 500) {
      rolloutCounts.errors.increment();
    }
  }

  @Scheduled(fixedRate = 5000)
  public void publishWindow() {
    Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant start = windowStart;
    windowStart = end;
    var snapshot = Map.copyOf(counts);
    counts.clear();
    snapshot.forEach(
        (rolloutId, value) -> {
          try {
            control
                .post()
                .uri("/internal/v1/rollouts/{id}/evidence-windows", rolloutId)
                .bodyValue(
                    Map.of(
                        "windowStart",
                        start,
                        "windowEnd",
                        end,
                        "candidateRequests",
                        value.requests.sum(),
                        "candidateErrors",
                        value.errors.sum()))
                .retrieve()
                .toBodilessEntity()
                .block();
          } catch (RuntimeException unavailable) {
            // Evidence publication failure cannot affect serving requests.
          }
        });
  }

  private static final class Counts {
    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
  }
}
