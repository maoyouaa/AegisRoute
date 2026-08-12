package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.control.service.RolloutService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rollouts")
public final class RolloutController {
  private final RolloutService service;

  public RolloutController(RolloutService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<RolloutResponse> create(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreateRolloutRequest request) {
    return response(service.create(idempotencyKey, request));
  }

  @PostMapping("/{id}/{action:shadow:start|canary:approve|pause|rollback}")
  ResponseEntity<RolloutResponse> mutate(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(name = HttpHeaders.IF_MATCH) String ifMatch,
      @RequestBody MutationRequest request) {
    return response(service.mutate(id, action, idempotencyKey, ifMatch, request));
  }

  private ResponseEntity<RolloutResponse> response(RolloutResponse response) {
    return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
  }
}
