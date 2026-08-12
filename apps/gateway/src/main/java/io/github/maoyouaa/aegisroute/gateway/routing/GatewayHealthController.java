package io.github.maoyouaa.aegisroute.gateway.routing;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class GatewayHealthController {
  private final RouteSnapshotStore snapshots;

  public GatewayHealthController(RouteSnapshotStore snapshots) {
    this.snapshots = snapshots;
  }

  @GetMapping("/health/live")
  Map<String, String> live() {
    return Map.of("status", "UP");
  }

  @GetMapping("/health/ready")
  ResponseEntity<Map<String, Object>> ready() {
    if (snapshots.current().isEmpty()) {
      return ResponseEntity.status(503).body(Map.of("status", "DOWN", "routeVersion", 0));
    }
    return ResponseEntity.ok(Map.of("status", "UP", "routeVersion", snapshots.version()));
  }
}
