package io.github.maoyouaa.aegisroute.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.maoyouaa.aegisroute.control.api.CreateRolloutRequest;
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

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = {"aegis.demo-bootstrap=false", "spring.task.scheduling.enabled=false"})
@Transactional
@org.springframework.context.annotation.Import(ReliabilityIntegrationTest.TimeConfiguration.class)
class ControlPersistenceIntegrationTest {
  @Autowired ReliabilityIntegrationTest.MutableClock clock;
  @Autowired io.github.maoyouaa.aegisroute.control.service.ReliabilityRepository reliability;

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
    clock.now = Instant.parse("2026-09-05T00:00:00Z");
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
    var canaryFixture =
        repository.updateState(rollout.id(), rollout.version(), RolloutState.CANARY, 10, now);
    long second = repository.createRouteRevision(canaryFixture, 10, now.plusSeconds(1)).version();
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
    UUID second = UUID.randomUUID();
    var request = new MutationRequest("test", "start synthetic shadow", null);

    var initial = rollouts.mutate(first.id(), "shadow:start", "same-key", "\"1\"", request);
    var replay = rollouts.mutate(first.id(), "shadow:start", "same-key", "\"1\"", request);

    assertThat(replay).isEqualTo(initial);
    assertThat(repository.find(first.id()).orElseThrow().version()).isEqualTo(2);
    assertThatThrownBy(() -> rollouts.mutate(second, "shadow:start", "same-key", "\"1\"", request))
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
    var shadowRoute = repository.latestRoute().orElseThrow();
    repository.acknowledge("gateway-test", shadowRoute, clock.instant(), clock.instant());
    submitWindow(shadowRoute, 0, 0, 20);
    var eligible = repository.find(rollout.id()).orElseThrow();
    var canary =
        rollouts.mutate(
            rollout.id(),
            "canary:approve",
            "rollback-canary",
            "\"" + eligible.version() + "\"",
            new MutationRequest("test", "approve canary", 1));
    assertThat(shadow.state()).isEqualTo(RolloutState.SHADOW);
    assertThat(canary.state()).isEqualTo(RolloutState.CANARY);

    var canaryRoute = repository.latestRoute().orElseThrow();
    assertThat(submitWindow(canaryRoute, 12, 4, 20).decisionId()).isNull();
    assertThat(submitWindow(canaryRoute, 12, 4, 20).decisionId()).isNull();
    var triggered = submitWindow(canaryRoute, 12, 4, 20);

    assertThat(triggered.consecutiveBreaches()).isEqualTo(3);
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

  private io.github.maoyouaa.aegisroute.control.api.WindowReceipt submitWindow(
      io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot route,
      int candidates,
      int errors,
      int pairs) {
    Instant start =
        io.github.maoyouaa.aegisroute.contracts.events.SampleIdentity.bucket(clock.instant());
    clock.now = start.plusSeconds(5);
    reliability.report(
        new io.github.maoyouaa.aegisroute.contracts.events.GatewayWindowReport(
            2,
            "gateway-test",
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            route.routeId(),
            route.version(),
            route.checksum(),
            start,
            start.plusSeconds(5),
            candidates + pairs,
            candidates,
            pairs,
            0));
    return evidence.evaluate(
        new io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2(
            2,
            io.github.maoyouaa.aegisroute.contracts.events.SampleIdentity.windowId(
                route.routeId(), start),
            1,
            route,
            start,
            start.plusSeconds(5),
            candidates + pairs,
            candidates,
            errors,
            pairs,
            0,
            0,
            0,
            0,
            "a".repeat(64)));
  }
}
