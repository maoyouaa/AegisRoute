package io.github.maoyouaa.aegisroute.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.maoyouaa.aegisroute.control.api.CreateRolloutRequest;
import io.github.maoyouaa.aegisroute.control.api.EvidenceSubmission;
import io.github.maoyouaa.aegisroute.control.api.MutationRequest;
import io.github.maoyouaa.aegisroute.control.service.EvidenceService;
import io.github.maoyouaa.aegisroute.control.service.RolloutRepository;
import io.github.maoyouaa.aegisroute.control.service.RolloutService;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"aegis.demo-bootstrap=false", "spring.task.scheduling.enabled=false"})
@Transactional
class ControlPersistenceIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6-alpine")
          .withDatabaseName("aegisroute")
          .withUsername("aegis")
          .withPassword("aegis-test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
  }

  private final RolloutRepository repository;
  private final RolloutService rollouts;
  private final EvidenceService evidence;
  private final JdbcTemplate jdbc;

  @Autowired
  ControlPersistenceIntegrationTest(
      RolloutRepository repository,
      RolloutService rollouts,
      EvidenceService evidence,
      JdbcTemplate jdbc) {
    this.repository = repository;
    this.rollouts = rollouts;
    this.evidence = evidence;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    jdbc.execute(
        "TRUNCATE gateway_convergence_evidence, rollback_decision_targets, rollout_decisions, policy_evaluations, evidence_windows, gateway_route_acks, rollout_audit_events, idempotency_records, route_revisions, rollouts CASCADE");
  }

  @Test
  void routeVersionsAreMonotonicAndDecisionsAreDatabaseImmutable() {
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    var request =
        new CreateRolloutRequest(
            "demo",
            "baseline-v1",
            "http://baseline:8080",
            "candidate-v1",
            "http://candidate:8080",
            "test",
            "integration evidence");
    var rollout = repository.create(request, UUID.randomUUID(), now);
    long first = repository.createRouteRevision(rollout, 0, now).version();
    long second = repository.createRouteRevision(rollout, 10, now.plusSeconds(1)).version();
    assertThat(second).isGreaterThan(first);

    UUID decision =
        repository.saveRollbackDecision(
            rollout.id(),
            second,
            repository.reserveRouteVersion(),
            now,
            now.plusSeconds(5),
            10,
            4,
            0.4,
            0.05,
            1,
            "a".repeat(64),
            java.util.List.of(),
            now.plusSeconds(5));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rollout_decisions SET threshold = 0.9 WHERE decision_id = ?", decision))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void idempotencyAdvisoryLockCanBeAcquiredWithoutMappingPostgresVoid() {
    repository.lockIdempotencyKey("integration-key");
  }

  @Test
  void idempotentMutationReplaysOnceAndRejectsCrossRolloutReuse() {
    var first = createRollout("first");
    var second = createRollout("second");
    var request = new MutationRequest("test", "start synthetic shadow", null);

    var initial = rollouts.mutate(first.id(), "shadow:start", "same-key", "\"1\"", request);
    var replay = rollouts.mutate(first.id(), "shadow:start", "same-key", "\"1\"", request);

    assertThat(replay).isEqualTo(initial);
    assertThat(repository.find(first.id()).orElseThrow().version()).isEqualTo(2);
    assertThatThrownBy(
            () -> rollouts.mutate(second.id(), "shadow:start", "same-key", "\"1\"", request))
        .isInstanceOf(io.github.maoyouaa.aegisroute.control.api.ApiException.class)
        .hasMessageContaining("different endpoint or payload");
  }

  @Test
  void staleIfMatchIsRejectedWithoutChangingTheRollout() {
    var rollout = createRollout("stale");
    var request = new MutationRequest("test", "start synthetic shadow", null);
    rollouts.mutate(rollout.id(), "shadow:start", "start-key", "\"1\"", request);

    assertThatThrownBy(() -> rollouts.mutate(rollout.id(), "pause", "pause-key", "\"1\"", request))
        .isInstanceOf(io.github.maoyouaa.aegisroute.control.api.ApiException.class)
        .hasMessageContaining("does not match");
    assertThat(repository.find(rollout.id()).orElseThrow().state()).isEqualTo(RolloutState.SHADOW);
  }

  @Test
  void thirdBreachedWindowCreatesOneImmutableRollbackChain() {
    var rollout = createRollout("rollback");
    var shadow =
        rollouts.mutate(
            rollout.id(),
            "shadow:start",
            "rollback-shadow",
            "\"1\"",
            new MutationRequest("test", "start shadow", null));
    var eligible = rollouts.markEligible(rollout.id(), true, "synthetic paired evidence");
    var canary =
        rollouts.mutate(
            rollout.id(),
            "canary:approve",
            "rollback-canary",
            "\"" + eligible.version() + "\"",
            new MutationRequest("test", "approve canary", 1));
    assertThat(shadow.state()).isEqualTo(RolloutState.SHADOW);
    assertThat(canary.state()).isEqualTo(RolloutState.CANARY);

    Instant start = Instant.parse("2026-08-12T00:00:00Z");
    assertThat(evidence.evaluate(rollout.id(), window(start)).rollbackTriggered()).isFalse();
    assertThat(evidence.evaluate(rollout.id(), window(start.plusSeconds(5))).rollbackTriggered())
        .isFalse();
    var triggered = evidence.evaluate(rollout.id(), window(start.plusSeconds(10)));

    assertThat(triggered.rollbackTriggered()).isTrue();
    assertThat(triggered.decisionId()).isNotNull();
    assertThat(repository.find(rollout.id()).orElseThrow().state())
        .isEqualTo(RolloutState.ROLLBACK_PROPAGATING);
    assertThat(
            jdbc.queryForObject(
                "SELECT candidate_ratio FROM route_revisions WHERE version=?",
                Integer.class,
                triggered.targetRouteVersion()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rollout_decisions WHERE rollout_id=?",
                Integer.class,
                rollout.id()))
        .isEqualTo(1);
  }

  private RolloutRepository.RolloutRow createRollout(String name) {
    Instant now = Instant.parse("2026-08-12T00:00:00Z");
    var request =
        new CreateRolloutRequest(
            name,
            "baseline-v1",
            "http://baseline:8080",
            "candidate-v1",
            "http://candidate:8080",
            "test",
            "integration evidence");
    var rollout = repository.create(request, UUID.randomUUID(), now);
    repository.createRouteRevision(rollout, 0, now);
    return rollout;
  }

  private EvidenceSubmission window(Instant start) {
    return new EvidenceSubmission(start, start.plusSeconds(5), 12, 4);
  }
}
