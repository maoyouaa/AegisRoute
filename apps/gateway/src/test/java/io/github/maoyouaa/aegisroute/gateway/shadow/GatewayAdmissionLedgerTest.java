package io.github.maoyouaa.aegisroute.gateway.shadow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class GatewayAdmissionLedgerTest {
  @Test
  void capacityLossStaysUnknownAfterSpaceFrees() {
    var clock = new MutableClock();
    var snapshots = mock(RouteSnapshotStore.class);
    var route =
        RouteSnapshot.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "b",
            "http://b",
            "c",
            "http://c",
            0,
            clock.instant(),
            RolloutState.SHADOW,
            100);
    when(snapshots.current()).thenReturn(Optional.empty());
    var sent = new ArrayList<String>();
    var ledger =
        new GatewayAdmissionLedger(
            "gateway-a",
            snapshots,
            WebClient.builder()
                .exchangeFunction(
                    request -> {
                      var output =
                          new org.springframework.mock.http.client.reactive.MockClientHttpRequest(
                              org.springframework.http.HttpMethod.POST,
                              java.net.URI.create("http://control"));
                      return request
                          .body()
                          .insert(
                              output,
                              new org.springframework.web.reactive.function.BodyInserter.Context() {
                                public java.util.List<
                                        org.springframework.http.codec.HttpMessageWriter<?>>
                                    messageWriters() {
                                  return ExchangeStrategies.withDefaults().messageWriters();
                                }

                                public Optional<
                                        org.springframework.http.server.reactive.ServerHttpRequest>
                                    serverRequest() {
                                  return Optional.empty();
                                }

                                public Map<String, Object> hints() {
                                  return Map.of();
                                }
                              })
                          .then(Mono.defer(output::getBodyAsString))
                          .map(
                              body -> {
                                sent.add(body);
                                return ClientResponse.create(HttpStatus.ACCEPTED).build();
                              });
                    }),
            "http://control",
            Duration.ZERO,
            clock,
            1);
    ledger.admit(route, "one", false);
    clock.now = clock.now.plusSeconds(5);
    ledger.admit(route, "lost", false);
    assertThat(ledger.unavailableReports()).isEqualTo(1);
    ledger.publish();
    assertThat(ledger.pendingReports()).isZero();
    ledger.admit(route, "after-loss", false);
    clock.now = clock.now.plusSeconds(5);
    ledger.publish();
    assertThat(sent)
        .hasSize(2)
        .allSatisfy(
            body -> assertThat(body).contains("\"coverageComplete\":false", "ADMISSION_LOSS"));
    assertThat(ledger.unavailableReports()).isEqualTo(1);
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-09-05T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
