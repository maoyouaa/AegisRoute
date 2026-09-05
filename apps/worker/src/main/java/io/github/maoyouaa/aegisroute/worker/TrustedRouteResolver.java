package io.github.maoyouaa.aegisroute.worker;

import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Internal Control is the authority; event-supplied URLs/checksums are not sufficient. */
@Component
public final class TrustedRouteResolver {
  private final WebClient control;
  private final ConcurrentHashMap<UUID, RouteSnapshot> verified = new ConcurrentHashMap<>();

  public TrustedRouteResolver(
      WebClient.Builder builder,
      @Value("${aegis.control-base-url:http://control:8081}") String url) {
    control = builder.baseUrl(url).build();
  }

  public boolean verify(RouteSnapshot supplied) {
    RouteSnapshot expected = verified.get(supplied.routeId());
    if (expected == null) {
      try {
        expected =
            control
                .get()
                .uri("/internal/v1/routes/{id}", supplied.routeId())
                .retrieve()
                .bodyToMono(RouteSnapshot.class)
                .block(Duration.ofMillis(750));
      } catch (WebClientResponseException.NotFound unknown) {
        return false;
      }
      if (expected == null)
        throw new IllegalStateException("Control route lookup returned no snapshot");
      if (!expected.validChecksum())
        throw new IllegalStateException("Control route checksum invalid");
      if (verified.size() >= 1024) {
        var iterator = verified.keySet().iterator();
        if (iterator.hasNext()) verified.remove(iterator.next());
      }
      verified.put(supplied.routeId(), expected);
    }
    return expected.equals(supplied);
  }
}
