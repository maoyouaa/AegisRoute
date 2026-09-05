package io.github.maoyouaa.aegisroute.worker;

import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public final class ServingEvidenceAggregator {
  private final DurableEvidenceStore store;
  private final WebClient control;
  private final Duration grace;

  public ServingEvidenceAggregator(
      DurableEvidenceStore store,
      WebClient.Builder builder,
      @Value("${aegis.control-base-url:http://control:8081}") String url,
      @Value("${aegis.evidence-grace:35s}") Duration grace) {
    this.store = store;
    this.control = builder.baseUrl(url).build();
    this.grace = grace;
  }

  @Scheduled(fixedDelay = 1000)
  public void publishWindow() {
    store.seal(Instant.now(), grace);
    for (var pending : store.pendingWindows()) {
      try {
        var window = store.read(pending.payload(), EvidenceWindowV2.class);
        String result =
            control
                .post()
                .uri("/internal/v2/evidence-windows")
                .bodyValue(window)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(2));
        if (result == null) return;
        store.acknowledgeWindow(pending.key(), result);
      } catch (WebClientResponseException rejected) {
        if (rejected.getStatusCode().value() == 409 || rejected.getStatusCode().value() == 400) {
          // Persist an explicit terminal receipt; preserve the sealed payload for inspection.
          store.acknowledgeWindow(pending.key(), rejected.getResponseBodyAsString());
        } else return;
      } catch (RuntimeException unavailable) {
        return;
      }
    }
  }
}
