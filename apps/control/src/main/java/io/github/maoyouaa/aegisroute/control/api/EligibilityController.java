package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.control.service.RolloutService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/rollouts")
public final class EligibilityController {
  private final RolloutService service;

  public EligibilityController(RolloutService service) {
    this.service = service;
  }

  @PostMapping("/{rolloutId}/eligibility")
  ResponseEntity<RolloutResponse> submit(
      @PathVariable UUID rolloutId, @RequestBody EligibilitySubmission submission) {
    if (submission.reason() == null || submission.reason().isBlank()) {
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST",
          "eligibility reason is required");
    }
    return ResponseEntity.ok(
        service.markEligible(rolloutId, submission.eligible(), submission.reason()));
  }
}
