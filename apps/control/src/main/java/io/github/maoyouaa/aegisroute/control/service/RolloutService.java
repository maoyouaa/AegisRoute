package io.github.maoyouaa.aegisroute.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.control.api.ApiException;
import io.github.maoyouaa.aegisroute.control.api.CreateRolloutRequest;
import io.github.maoyouaa.aegisroute.control.api.MutationRequest;
import io.github.maoyouaa.aegisroute.control.api.RolloutResponse;
import io.github.maoyouaa.aegisroute.control.persistence.CanonicalInput;
import io.github.maoyouaa.aegisroute.domain.rollout.InvalidRolloutTransitionException;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutAction;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutTransitions;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RolloutService {
  private final RolloutRepository repository;
  private final CanonicalInput canonicalInput;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public RolloutService(
      RolloutRepository repository, CanonicalInput canonicalInput, ObjectMapper objectMapper) {
    this(repository, canonicalInput, objectMapper, Clock.systemUTC());
  }

  RolloutService(
      RolloutRepository repository,
      CanonicalInput canonicalInput,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.canonicalInput = canonicalInput;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public RolloutResponse create(String idempotencyKey, CreateRolloutRequest request) {
    validateCreation(request);
    String endpoint = "POST /api/v1/rollouts";
    return idempotent(
        idempotencyKey,
        endpoint,
        request,
        ignored -> {
          Instant now = clock.instant();
          var rollout = repository.create(request, UUID.randomUUID(), now);
          var route = repository.createRouteRevision(rollout, 0, now);
          repository.audit(
              rollout.id(), "CREATE", request.actor(), request.reason(), 0, rollout.version(), now);
          return rollout.response(route.version());
        });
  }

  @Transactional
  public RolloutResponse mutate(
      UUID rolloutId,
      String actionPath,
      String idempotencyKey,
      String ifMatch,
      MutationRequest request) {
    long expectedVersion = parseIfMatch(ifMatch);
    requireActorAndReason(request.actor(), request.reason());
    String endpoint = "POST /api/v1/rollouts/" + rolloutId + "/" + actionPath;
    return idempotent(
        idempotencyKey,
        endpoint,
        request,
        ignored -> doMutate(rolloutId, actionPath, expectedVersion, request));
  }

  @Transactional
  public RolloutResponse markEligible(UUID rolloutId, boolean eligible, String reason) {
    var rollout = find(rolloutId);
    RolloutState requestedState = eligible ? RolloutState.ELIGIBLE : RolloutState.BLOCKED;
    if (rollout.state() == requestedState) {
      return rollout.response(latestRouteVersion());
    }
    RolloutAction action = eligible ? RolloutAction.MARK_ELIGIBLE : RolloutAction.MARK_BLOCKED;
    RolloutState next = transition(rollout.state(), action);
    Instant now = clock.instant();
    var updated = update(rollout, next, rollout.candidateRatio(), now);
    repository.audit(
        rollout.id(), action.name(), "worker", reason, rollout.version(), updated.version(), now);
    return updated.response(latestRouteVersion());
  }

  private RolloutResponse doMutate(
      UUID rolloutId, String actionPath, long expectedVersion, MutationRequest request) {
    var rollout = find(rolloutId);
    if (rollout.version() != expectedVersion) {
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED,
          "PRECONDITION_FAILED",
          "If-Match does not match the current rollout version");
    }
    Instant now = clock.instant();
    RolloutState next;
    int ratio = rollout.candidateRatio();
    RolloutAction action;
    switch (actionPath) {
      case "shadow:start" -> {
        action = RolloutAction.START_SHADOW;
        next = transition(rollout.state(), action);
      }
      case "canary:approve" -> {
        action = RolloutAction.APPROVE_CANARY;
        if (request.candidateRatio() == null) {
          throw new ApiException(
              HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "candidateRatio is required");
        }
        ratio = request.candidateRatio();
        try {
          next = RolloutTransitions.approveCanary(rollout.state(), rollout.candidateRatio(), ratio);
        } catch (InvalidRolloutTransitionException invalid) {
          throw stateConflict(invalid.getMessage());
        }
      }
      case "pause" -> {
        action = RolloutAction.PAUSE;
        next = transition(rollout.state(), action);
        ratio = 0;
      }
      case "rollback" -> {
        action = RolloutAction.ROLLBACK;
        next = transition(rollout.state(), action);
        ratio = 0;
      }
      default ->
          throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown rollout action");
    }

    var updated = update(rollout, next, ratio, now);
    var route = repository.createRouteRevision(updated, ratio, now);
    repository.audit(
        rollout.id(),
        action.name(),
        request.actor(),
        request.reason(),
        rollout.version(),
        updated.version(),
        now);
    return updated.response(route.version());
  }

  private RolloutRepository.RolloutRow update(
      RolloutRepository.RolloutRow rollout, RolloutState next, int ratio, Instant now) {
    try {
      return repository.updateState(rollout.id(), rollout.version(), next, ratio, now);
    } catch (RolloutRepository.OptimisticConflictException conflict) {
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", "Rollout changed concurrently");
    }
  }

  private RolloutState transition(RolloutState current, RolloutAction action) {
    try {
      return RolloutTransitions.apply(current, action);
    } catch (InvalidRolloutTransitionException invalid) {
      throw stateConflict(invalid.getMessage());
    }
  }

  private ApiException stateConflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "ROLLOUT_STATE_CONFLICT", message);
  }

  private RolloutRepository.RolloutRow find(UUID rolloutId) {
    return repository
        .find(rolloutId)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "ROLLOUT_NOT_FOUND", "Rollout not found"));
  }

  private long latestRouteVersion() {
    return repository.latestRoute().map(route -> route.version()).orElse(0L);
  }

  private RolloutResponse idempotent(
      String key, String endpoint, Object payload, Function<Void, RolloutResponse> operation) {
    if (key == null || key.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER", "Idempotency-Key is required");
    }
    Instant now = clock.instant();
    String hash = canonicalInput.hash(endpoint, payload);
    repository.lockIdempotencyKey(key);
    var existing = repository.findIdempotency(key, now);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(hash)) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was already used for a different endpoint or payload");
      }
      try {
        return objectMapper.readValue(existing.get().responseBody(), RolloutResponse.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Stored idempotency result is invalid", e);
      }
    }
    RolloutResponse response = operation.apply(null);
    try {
      repository.saveIdempotency(key, hash, 200, objectMapper.writeValueAsString(response), now);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot store idempotency result", e);
    }
    return response;
  }

  private long parseIfMatch(String ifMatch) {
    if (ifMatch == null || !ifMatch.matches("\"[1-9][0-9]*\"")) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_IF_MATCH",
          "If-Match must be a strong quoted rollout version");
    }
    return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
  }

  private void validateCreation(CreateRolloutRequest request) {
    if (request.name() == null
        || request.name().isBlank()
        || request.baselineDeploymentId() == null
        || request.baselineDeploymentId().isBlank()
        || request.baselineBaseUrl() == null
        || request.baselineBaseUrl().isBlank()
        || request.candidateDeploymentId() == null
        || request.candidateDeploymentId().isBlank()
        || request.candidateBaseUrl() == null
        || request.candidateBaseUrl().isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Deployment details are required");
    }
    requireActorAndReason(request.actor(), request.reason());
  }

  private void requireActorAndReason(String actor, String reason) {
    if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "actor and reason are required");
    }
  }
}
