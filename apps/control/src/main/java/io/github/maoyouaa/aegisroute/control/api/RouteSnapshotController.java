package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.control.service.RolloutRepository;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/routes")
public final class RouteSnapshotController {
  private final RolloutRepository repository;

  public RouteSnapshotController(RolloutRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/current")
  ResponseEntity<RouteSnapshot> current(
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    var current = repository.latestRoute();
    if (current.isEmpty()) return ResponseEntity.notFound().build();
    String etag = "\"" + current.get().version() + "\"";
    if (etag.equals(ifNoneMatch)) return ResponseEntity.status(304).eTag(etag).build();
    return ResponseEntity.ok().eTag(etag).body(current.get());
  }

  @PostMapping("/applied")
  ResponseEntity<Void> applied(@RequestBody RouteAppliedRequest request) {
    RouteSnapshot current =
        repository
            .latestRoute()
            .orElseThrow(
                () ->
                    new ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "ROUTE_SNAPSHOT_UNAVAILABLE",
                        "No route snapshot exists"));
    if (!current.routeId().equals(request.routeId())
        || current.version() != request.routeVersion()
        || !current.checksum().equals(request.checksum())) {
      throw new ApiException(
          org.springframework.http.HttpStatus.CONFLICT,
          "ROUTE_APPLIED_MISMATCH",
          "Acknowledgement does not match current route");
    }
    repository.acknowledge(
        request.gatewayInstanceId(), current, request.appliedAt(), Instant.now());
    return ResponseEntity.accepted().build();
  }
}
