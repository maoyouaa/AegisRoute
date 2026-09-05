package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.contracts.events.EvidenceWindowV2;
import io.github.maoyouaa.aegisroute.contracts.events.GatewayWindowReport;
import io.github.maoyouaa.aegisroute.control.service.EvidenceService;
import io.github.maoyouaa.aegisroute.control.service.ReliabilityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v2")
public final class ReliabilityController {
  private final ReliabilityRepository repository;
  private final EvidenceService evidence;
  private final io.github.maoyouaa.aegisroute.control.service.ReliabilityStatus status;

  public ReliabilityController(
      ReliabilityRepository repository,
      EvidenceService evidence,
      io.github.maoyouaa.aegisroute.control.service.ReliabilityStatus status) {
    this.repository = repository;
    this.evidence = evidence;
    this.status = status;
  }

  @GetMapping("/status")
  io.github.maoyouaa.aegisroute.control.service.ReliabilityStatus.Status status() {
    return status.read();
  }

  @PostMapping("/gateway-windows")
  ResponseEntity<Void> report(@RequestBody GatewayWindowReport report) {
    repository.report(report);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/evidence-windows")
  WindowReceipt window(@RequestBody EvidenceWindowV2 window) {
    return evidence.evaluate(window);
  }
}
