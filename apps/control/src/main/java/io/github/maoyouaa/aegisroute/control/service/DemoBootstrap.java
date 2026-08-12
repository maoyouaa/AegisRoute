package io.github.maoyouaa.aegisroute.control.service;

import io.github.maoyouaa.aegisroute.control.api.CreateRolloutRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoBootstrap implements ApplicationRunner {
  private final RolloutRepository repository;
  private final boolean enabled;
  private final String baselineBaseUrl;
  private final String candidateBaseUrl;

  public DemoBootstrap(
      RolloutRepository repository,
      @Value("${aegis.demo-bootstrap:false}") boolean enabled,
      @Value("${aegis.demo-baseline-base-url:http://baseline:8080}") String baselineBaseUrl,
      @Value("${aegis.demo-candidate-base-url:http://candidate:8080}") String candidateBaseUrl) {
    this.repository = repository;
    this.enabled = enabled;
    this.baselineBaseUrl = baselineBaseUrl;
    this.candidateBaseUrl = candidateBaseUrl;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled || repository.latestRoute().isPresent()) return;
    Instant now = Instant.now();
    var request =
        new CreateRolloutRequest(
            "Synthetic demo rollout",
            "baseline-v1",
            baselineBaseUrl,
            "candidate-v1",
            candidateBaseUrl,
            "compose-bootstrap",
            "Create the synthetic local demonstration route");
    var rollout = repository.create(request, UUID.randomUUID(), now);
    repository.createRouteRevision(rollout, 0, now);
    repository.audit(
        rollout.id(), "CREATE", request.actor(), request.reason(), 0, rollout.version(), now);
  }
}
