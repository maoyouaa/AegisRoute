package io.github.maoyouaa.aegisroute.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class RouteSnapshotPollerTest {
  private static final UUID ROUTE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ROLLOUT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void boundsUnavailableControlAndRecoversOnTheNextPoll() throws Exception {
    RouteSnapshot route = route();
    byte[] response =
        JsonMapper.builder().addModule(new JavaTimeModule()).build().writeValueAsBytes(route);
    AtomicInteger snapshotRequests = new AtomicInteger();
    var executor = Executors.newCachedThreadPool();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/internal/v1/routes/current",
        exchange -> {
          if (snapshotRequests.incrementAndGet() == 1) {
            delayFirstResponse(exchange);
            return;
          }
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("ETag", "\"1\"");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/routes/applied",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(202, -1);
          exchange.close();
        });
    server.start();

    try {
      var store = new RouteSnapshotStore();
      var poller =
          new RouteSnapshotPoller(
              store,
              WebClient.builder(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "gateway-test",
              Duration.ofMillis(500));

      Instant started = Instant.now();
      poller.poll();

      assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofMillis(1_500));
      assertThat(store.current()).isEmpty();

      poller.poll();

      assertThat(store.current()).contains(route);
      assertThat(snapshotRequests).hasValue(2);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static void delayFirstResponse(HttpExchange exchange) {
    try {
      Thread.sleep(2_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private static RouteSnapshot route() throws IOException {
    String checksum =
        RouteChecksum.calculate(
            ROUTE_ID,
            ROLLOUT_ID,
            1,
            "baseline-v1",
            "http://baseline:8080",
            "candidate-v1",
            "http://candidate:8080",
            0);
    return new RouteSnapshot(
        ROUTE_ID,
        ROLLOUT_ID,
        1,
        "baseline-v1",
        "http://baseline:8080",
        "candidate-v1",
        "http://candidate:8080",
        0,
        checksum,
        Instant.parse("2026-08-17T00:00:00Z"));
  }
}
