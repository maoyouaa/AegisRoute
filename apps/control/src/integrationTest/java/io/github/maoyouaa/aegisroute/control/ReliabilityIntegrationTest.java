package io.github.maoyouaa.aegisroute.control;

import static org.assertj.core.api.Assertions.*;

import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.control.api.*;
import io.github.maoyouaa.aegisroute.control.service.*;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = {"aegis.demo-bootstrap=false"})
@Import(ReliabilityIntegrationTest.TimeConfiguration.class)
class ReliabilityIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired RolloutService rollouts;
  @Autowired RolloutRepository routes;
  @Autowired ReliabilityRepository reliability;
  @Autowired EvidenceService evidence;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  final UUID bootA = UUID.randomUUID();
  final UUID bootB = UUID.randomUUID();

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE rollouts, idempotency_records, gateway_route_acks CASCADE");
    clock.now = Instant.parse("2026-09-05T00:00:00Z");
  }

  @Test
  void eachHumanStepRequiresFreshCurrentRouteEvidenceAndConsumesItOnce() {
    var rollout = shadow();
    register();
    assertThatThrownBy(() -> approve(rollout.id(), 1)).isInstanceOf(ApiException.class);
    qualify();
    for (int ratio : new int[] {1, 10, 50, 100}) {
      var before = routes.find(rollout.id()).orElseThrow();
      String key = UUID.randomUUID().toString();
      var request = new MutationRequest("test", "human approval with fresh evidence", ratio);
      var promoted =
          rollouts.mutate(before.id(), "canary:approve", key, quote(before.version()), request);
      assertThat(promoted.candidateRatio()).isEqualTo(ratio);
      assertThat(
              rollouts.mutate(before.id(), "canary:approve", key, quote(before.version()), request))
          .isEqualTo(promoted);
      if (ratio != 100) {
        int next = ratio == 1 ? 10 : ratio == 10 ? 50 : 100;
        assertThatThrownBy(() -> approve(before.id(), next))
            .hasMessageContaining("FRESH_EVIDENCE_REQUIRED");
        qualify();
      }
    }
    assertThat(count("promotion_evidence_uses")).isEqualTo(4);
  }

  @Test
  void staleEvidenceAndInsufficientCoverageCannotPromote() {
    var rollout = shadow();
    register();
    var route = routes.latestRoute().orElseThrow();
    Instant start = nextWindow();
    reports(route, start, 100, 0, 100);
    var insufficient = evidence.evaluate(window(route, start, 5, 0, 0, 5));
    assertThat(insufficient.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    assertThat(insufficient.eligible()).isFalse();
    qualify();
    clock.now = clock.now.plusSeconds(121);
    assertThatThrownBy(() -> approve(rollout.id(), 1))
        .hasMessageContaining("FRESH_EVIDENCE_REQUIRED");
  }

  @Test
  void duplicateAndPostRollbackReplayReturnOriginalDecisionWithoutSecondMutation() {
    var rollout = canary();
    var route = routes.latestRoute().orElseThrow();
    WindowReceipt last = null;
    EvidenceWindowV2 submitted = null;
    for (int i = 1; i <= 3; i++) {
      Instant start = nextWindow();
      reports(route, start, 40, 20, 20);
      submitted = window(route, start, 40, 20, 4, 20);
      last = evidence.evaluate(submitted);
      assertThat(last.consecutiveBreaches()).isEqualTo(i);
      assertThat(evidence.evaluate(submitted)).isEqualTo(last);
    }
    assertThat(last.decisionId()).isNotNull();
    assertThat(count("rollout_decisions")).isEqualTo(1);
    assertThat(count("rollback_sources")).isEqualTo(1);
    assertThat(routes.find(rollout.id()).orElseThrow().state())
        .isEqualTo(RolloutState.ROLLBACK_PROPAGATING);
    assertThat(evidence.evaluate(submitted)).isEqualTo(last);
    var changed = window(route, submitted.windowStart(), 40, 20, 5, 20);
    assertThatThrownBy(() -> evidence.evaluate(changed)).hasMessageContaining("WINDOW_ID_REUSED");
  }

  @Test
  void gapResetsStreakAndOutOfOrderWindowIsRejected() {
    canary();
    var route = routes.latestRoute().orElseThrow();
    Instant first = nextWindow();
    reports(route, first, 40, 20, 20);
    assertThat(evidence.evaluate(window(route, first, 40, 20, 4, 20)).consecutiveBreaches())
        .isEqualTo(1);
    clock.now = clock.now.plusSeconds(5);
    Instant afterGap = nextWindow();
    reports(route, afterGap, 40, 20, 20);
    assertThat(evidence.evaluate(window(route, afterGap, 40, 20, 4, 20)).consecutiveBreaches())
        .isEqualTo(1);
    assertThatThrownBy(() -> evidence.evaluate(window(route, first.plusSeconds(5), 40, 20, 4, 20)))
        .hasMessageContaining("OUT_OF_ORDER_OR_OVERLAPPING_WINDOW");
    assertThat(count("rollout_decisions")).isZero();
  }

  @Test
  void concurrentApprovalsUseOneEvidenceWindowAndKeep412Contract() throws Exception {
    var rollout = shadow();
    register();
    qualify();
    var before = routes.find(rollout.id()).orElseThrow();
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> call =
          () -> {
            try {
              rollouts.mutate(
                  before.id(),
                  "canary:approve",
                  UUID.randomUUID().toString(),
                  quote(before.version()),
                  new MutationRequest("test", "concurrent human approval", 1));
              return 200;
            } catch (ApiException rejected) {
              return rejected.status().value();
            }
          };
      var results = pool.invokeAll(List.of(call, call));
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(200, 412);
    }
    assertThat(count("promotion_evidence_uses")).isEqualTo(1);
  }

  @Test
  void manualRollbackEmptyTargetsRemainUnknown() {
    var rollout = shadow();
    var paused = mutate(rollout.id(), "pause", null);
    var rollback = mutate(paused.id(), "rollback", null);
    evidence.detectConvergence();
    assertThat(routes.find(rollout.id()).orElseThrow().state())
        .isEqualTo(RolloutState.ROLLBACK_PROPAGATING);
    assertThat(count("rollout_decisions")).isEqualTo(1);
    assertThat(count("rollback_decision_targets")).isZero();
    assertThat(count("gateway_convergence_evidence")).isZero();
    assertThat(rollback.candidateRatio()).isZero();
  }

  @Test
  void skippedTargetAndOfflineGatewayDoNotConvergeButExactLateAckDoes() {
    var rollout = canary();
    clock.now =
        clock.now.plusSeconds(60); // Both known gateways are now offline by the old 15-second test.
    mutate(rollout.id(), "rollback", null);
    var target = routes.latestRoute().orElseThrow();
    var later =
        RouteSnapshot.create(
            UUID.randomUUID(),
            target.rolloutId(),
            routes.reserveRouteVersion(),
            target.baselineDeploymentId(),
            target.baselineBaseUrl(),
            target.candidateDeploymentId(),
            target.candidateBaseUrl(),
            0,
            clock.instant(),
            RolloutState.ROLLBACK_PROPAGATING,
            0);
    routes.insertRoute(later);
    routes.acknowledge("gateway-a", later, clock.instant(), clock.instant());
    routes.acknowledge("gateway-b", target, clock.instant(), clock.instant());
    evidence.detectConvergence();
    assertThat(routes.find(rollout.id()).orElseThrow().state())
        .isEqualTo(RolloutState.ROLLBACK_PROPAGATING);
    assertThat(count("rollback_decision_targets")).isEqualTo(2);
    routes.acknowledge("gateway-a", target, clock.instant(), clock.instant());
    routes.acknowledge("gateway-a", target, clock.instant(), clock.instant());
    evidence.detectConvergence();
    assertThat(routes.find(rollout.id()).orElseThrow().state()).isEqualTo(RolloutState.ROLLED_BACK);
    assertThat(count("gateway_convergence_evidence")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT route_version FROM gateway_route_acks WHERE gateway_instance_id='gateway-a'",
                Long.class))
        .isEqualTo(later.version());
    assertThatThrownBy(() -> jdbc.update("UPDATE gateway_ack_history SET route_version=999"))
        .hasMessageContaining("append-only");
  }

  @Test
  void activeOwnerCreationAndWrongRouteEvidenceAreRejected() {
    shadow();
    assertThatThrownBy(this::create).hasMessageContaining("active rollout");
    var route = routes.latestRoute().orElseThrow();
    var wrong =
        RouteSnapshot.create(
            route.routeId(),
            route.rolloutId(),
            route.version(),
            route.baselineDeploymentId(),
            route.baselineBaseUrl(),
            "wrong-candidate",
            route.candidateBaseUrl(),
            0,
            route.createdAt(),
            RolloutState.SHADOW,
            100);
    Instant start = nextWindow();
    assertThatThrownBy(() -> evidence.evaluate(window(wrong, start, 20, 0, 0, 20)))
        .hasMessageContaining("WINDOW_ROUTE_MISMATCH");
  }

  @Test
  void expiredIdempotencyReplacementIsStoredAndReplayed() {
    var first = shadow();
    var paused = mutate(first.id(), "pause", null);
    String key = "expired-reuse";
    var body = new MutationRequest("test", "manual rollback", null);
    var rollback = rollouts.mutate(paused.id(), "rollback", key, quote(paused.version()), body);
    jdbc.update(
        "UPDATE idempotency_records SET expires_at=? WHERE idempotency_key=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        key);
    // Reuse the expired key for a different endpoint; new result must replace its row.
    routes.updateState(
        first.id(), rollback.version(), RolloutState.ROLLED_BACK, 0, clock.instant());
    var request =
        new CreateRolloutRequest(
            "replacement",
            "b",
            "http://baseline:8080",
            "c",
            "http://candidate:8080",
            "test",
            "new synthetic owner");
    var replacement = rollouts.create(key, request);
    assertThat(rollouts.create(key, request)).isEqualTo(replacement);
    assertThat(count("rollouts")).isEqualTo(2);
  }

  @Test
  void replacementBootPartialCoverageIsUnknownAndUnpairedBaselineFailureBlocksEligibility() {
    shadow();
    register();
    var route = routes.latestRoute().orElseThrow();
    Instant start = nextWindow();
    reliability.report(
        new GatewayWindowReport(
            2,
            "gateway-a",
            UUID.randomUUID(),
            route.routeId(),
            route.version(),
            route.checksum(),
            start,
            start.plusSeconds(5),
            10,
            0,
            10,
            0,
            false,
            "BOOT_PARTIAL"));
    reliability.report(
        new GatewayWindowReport(
            2,
            "gateway-b",
            bootB,
            route.routeId(),
            route.version(),
            route.checksum(),
            start,
            start.plusSeconds(5),
            10,
            0,
            10,
            0));
    assertThat(evidence.evaluate(window(route, start, 20, 0, 0, 20)).status())
        .isEqualTo("INSUFFICIENT_EVIDENCE");
    start = nextWindow();
    reports(route, start, 20, 0, 20);
    var failedBaseline =
        new EvidenceWindowV2(
            2,
            SampleIdentity.windowId(route.routeId(), start),
            1,
            route,
            start,
            start.plusSeconds(5),
            20,
            0,
            0,
            19,
            1,
            0,
            1,
            0,
            "b".repeat(64));
    assertThat(evidence.evaluate(failedBaseline).eligible()).isFalse();
  }

  @Test
  void breachedCanaryServingCannotAuthorizeNextStepWithHealthyShadow() {
    var rollout = canary();
    var route = routes.latestRoute().orElseThrow();
    qualify(); // An earlier healthy unconsumed window must be superseded by this failure.
    Instant start = nextWindow();
    reports(route, start, 40, 20, 20);
    var result = evidence.evaluate(window(route, start, 40, 20, 4, 20));
    assertThat(result.consecutiveBreaches()).isEqualTo(1);
    assertThat(result.eligible()).isFalse();
    assertThatThrownBy(() -> approve(rollout.id(), 10))
        .hasMessageContaining("FRESH_EVIDENCE_REQUIRED");
  }

  @Test
  void impossiblePairCountCannotReachPolicyPersistence() {
    shadow();
    register();
    var route = routes.latestRoute().orElseThrow();
    Instant start = nextWindow();
    reports(route, start, 20, 0, 20);
    assertThatThrownBy(() -> evidence.evaluate(window(route, start, 19, 0, 0, 20)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid immutable evidence");
    assertThat(count("evidence_windows_v2")).isZero();
    assertThat(count("promotion_evidence_uses")).isZero();
  }

  private RolloutResponse create() {
    return rollouts.create(
        UUID.randomUUID().toString(),
        new CreateRolloutRequest(
            "synthetic",
            "baseline",
            "http://baseline:8080",
            "candidate",
            "http://candidate:8080",
            "test",
            "integration fixture"));
  }

  private RolloutResponse shadow() {
    var created = create();
    return mutate(created.id(), "shadow:start", null);
  }

  private RolloutResponse canary() {
    var rollout = shadow();
    register();
    qualify();
    return approve(rollout.id(), 1);
  }

  private RolloutResponse approve(UUID id, int ratio) {
    return mutate(id, "canary:approve", ratio);
  }

  private RolloutResponse mutate(UUID id, String action, Integer ratio) {
    return rollouts.mutate(
        id,
        action,
        UUID.randomUUID().toString(),
        quote(routes.find(id).orElseThrow().version()),
        new MutationRequest("test", "synthetic state transition", ratio));
  }

  private void register() {
    var route = routes.latestRoute().orElseThrow();
    for (String instance : List.of("gateway-a", "gateway-b"))
      routes.acknowledge(instance, route, clock.instant(), clock.instant());
  }

  private Instant nextWindow() {
    Instant start = SampleIdentity.bucket(clock.instant());
    clock.now = start.plusSeconds(5);
    return start;
  }

  private void qualify() {
    var route = routes.latestRoute().orElseThrow();
    Instant start = nextWindow();
    int candidate = route.candidateRatio() == 0 ? 0 : 20;
    reports(route, start, 40, candidate, 40 - candidate);
    assertThat(evidence.evaluate(window(route, start, 40, candidate, 0, 40 - candidate)).eligible())
        .isTrue();
  }

  private void reports(RouteSnapshot route, Instant start, int total, int candidate, int shadow) {
    reliability.report(
        new GatewayWindowReport(
            2,
            "gateway-a",
            bootA,
            route.routeId(),
            route.version(),
            route.checksum(),
            start,
            start.plusSeconds(5),
            total / 2,
            candidate / 2,
            shadow / 2,
            0));
    reliability.report(
        new GatewayWindowReport(
            2,
            "gateway-b",
            bootB,
            route.routeId(),
            route.version(),
            route.checksum(),
            start,
            start.plusSeconds(5),
            total - total / 2,
            candidate - candidate / 2,
            shadow - shadow / 2,
            0));
  }

  private EvidenceWindowV2 window(
      RouteSnapshot route, Instant start, int serving, int candidate, int errors, int pairs) {
    return new EvidenceWindowV2(
        2,
        SampleIdentity.windowId(route.routeId(), start),
        1,
        route,
        start,
        start.plusSeconds(5),
        serving,
        candidate,
        errors,
        pairs,
        0,
        0,
        0,
        0,
        "a".repeat(64));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private String quote(long version) {
    return "\"" + version + "\"";
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static final class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-09-05T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
