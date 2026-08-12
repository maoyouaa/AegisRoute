package io.github.maoyouaa.aegisroute.gateway.routing;

import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public final class RouteSnapshotPoller {
  private final RouteSnapshotStore store;
  private final WebClient control;
  private final String instanceId;
  private final AtomicReference<String> etag = new AtomicReference<>();

  public RouteSnapshotPoller(
      RouteSnapshotStore store,
      WebClient.Builder builder,
      @Value("${aegis.control-base-url:http://localhost:8081}") String controlBaseUrl,
      @Value("${aegis.gateway-instance-id:${HOSTNAME:gateway-local}}") String instanceId) {
    this.store = store;
    this.control = builder.baseUrl(controlBaseUrl).build();
    this.instanceId = instanceId;
  }

  @Scheduled(fixedDelayString = "${aegis.snapshot-poll-interval:1000}")
  public void poll() {
    try {
      var response =
          control
              .get()
              .uri("/internal/v1/routes/current")
              .headers(
                  headers -> {
                    if (etag.get() != null) headers.setIfNoneMatch(etag.get());
                  })
              .exchangeToMono(
                  value -> {
                    if (value.statusCode() == HttpStatus.NOT_MODIFIED)
                      return reactor.core.publisher.Mono.empty();
                    if (value.statusCode().is2xxSuccessful()) {
                      String nextEtag = value.headers().asHttpHeaders().getETag();
                      return value
                          .bodyToMono(RouteSnapshot.class)
                          .map(route -> new SnapshotResponse(route, nextEtag));
                    }
                    return value.releaseBody().then(reactor.core.publisher.Mono.empty());
                  })
              .block();
      if (response != null && store.apply(response.snapshot())) {
        etag.set(response.etag());
        acknowledge(response.snapshot());
      } else if (store.current().isPresent()) {
        acknowledge(store.current().orElseThrow());
      }
    } catch (RuntimeException unavailable) {
      // Last-known-good remains active; metrics expose its increasing age.
    }
  }

  private void acknowledge(RouteSnapshot route) {
    try {
      control
          .post()
          .uri("/internal/v1/routes/applied")
          .bodyValue(
              Map.of(
                  "gatewayInstanceId", instanceId,
                  "routeId", route.routeId(),
                  "routeVersion", route.version(),
                  "checksum", route.checksum(),
                  "appliedAt", store.appliedAt() == null ? Instant.now() : store.appliedAt()))
          .retrieve()
          .toBodilessEntity()
          .block();
    } catch (RuntimeException ignored) {
      // Acknowledgement failure cannot invalidate serving LKG.
    }
  }

  private record SnapshotResponse(RouteSnapshot snapshot, String etag) {}
}
