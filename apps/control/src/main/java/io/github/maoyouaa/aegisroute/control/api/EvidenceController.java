package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.control.service.EvidenceService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/rollouts")
public class EvidenceController {
  private final EvidenceService service;

  public EvidenceController(EvidenceService service) {
    this.service = service;
  }

  @PostMapping("/{rolloutId}/evidence-windows")
  ResponseEntity<PolicyEvaluationResponse> submit(
      @PathVariable UUID rolloutId, @RequestBody EvidenceSubmission submission) {
    return ResponseEntity.ok(service.evaluate(rolloutId, submission));
  }
}
