package io.github.maoyouaa.aegisroute.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionChunk;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionResponse;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ObservedOutcome;
import io.github.maoyouaa.aegisroute.contracts.events.ServingObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.ShadowRequestedV1;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.domain.routing.StableSampler;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import io.github.maoyouaa.aegisroute.gateway.shadow.BoundedShadowQueue;
import io.github.maoyouaa.aegisroute.gateway.shadow.ShadowEnvelope;
import io.github.maoyouaa.aegisroute.provider.FailureClassifier;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public final class ChatController {
  private final RouteSnapshotStore snapshots;
  private final BoundedShadowQueue shadowQueue;
  private final ObjectMapper eventMapper;
  private final OpenAiProviderFactory providers;

  public ChatController(
      RouteSnapshotStore snapshots,
      BoundedShadowQueue shadowQueue,
      ObjectMapper objectMapper,
      OpenAiProviderFactory providers) {
    this.snapshots = snapshots;
    this.shadowQueue = shadowQueue;
    this.eventMapper = objectMapper;
    this.providers = providers;
  }

  @PostMapping(
      path = "/v1/chat/completions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE,
      headers = "Accept!=text/event-stream")
  Mono<ResponseEntity<?>> complete(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
    RouteSnapshot route = requireRoute();
    String resolvedRequestId = requestId == null ? UUID.randomUUID().toString() : requestId;
    boolean candidate = StableSampler.selectsCandidate(resolvedRequestId, route.candidateRatio());
    UUID sampleId = enqueueShadow(route, resolvedRequestId, request);
    String deploymentId = candidate ? route.candidateDeploymentId() : route.baselineDeploymentId();
    String baseUrl = candidate ? route.candidateBaseUrl() : route.baselineBaseUrl();
    Instant started = Instant.now();
    return providers
        .provider(baseUrl)
        .complete(request, new ProviderCallContext(resolvedRequestId, Duration.ofSeconds(30)))
        .<ResponseEntity<?>>map(
            response -> {
              enqueueServing(
                  route,
                  resolvedRequestId,
                  deploymentId,
                  candidate,
                  ObservedOutcome.SUCCESS,
                  200,
                  started);
              if (!candidate) {
                enqueueBaseline(
                    route, sampleId, resolvedRequestId, ObservedOutcome.SUCCESS, 200, started);
              }
              return ResponseEntity.ok(
                  new ChatCompletionResponse(
                      "chatcmpl-" + UUID.randomUUID(),
                      "chat.completion",
                      Instant.now().getEpochSecond(),
                      response.model(),
                      List.of(
                          new ChatCompletionResponse.Choice(
                              0, new ChatMessage("assistant", response.content()), "stop")),
                      new ChatCompletionResponse.Usage(0, 0, 0)));
            })
        .onErrorResume(
            failure -> {
              enqueueServing(
                  route,
                  resolvedRequestId,
                  deploymentId,
                  candidate,
                  ObservedOutcome.HTTP_ERROR,
                  502,
                  started);
              if (!candidate) {
                enqueueBaseline(
                    route, sampleId, resolvedRequestId, ObservedOutcome.HTTP_ERROR, 502, started);
              }
              return Mono.just(
                  ResponseEntity.status(502)
                      .body(new ApiError("PROVIDER_FAILURE", deploymentId + " failed")));
            });
  }

  @PostMapping(
      path = "/v1/chat/completions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  Flux<ServerSentEvent<Object>> stream(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
    RouteSnapshot route = requireRoute();
    String resolvedRequestId = requestId == null ? UUID.randomUUID().toString() : requestId;
    boolean candidate = StableSampler.selectsCandidate(resolvedRequestId, route.candidateRatio());
    UUID sampleId = enqueueShadow(route, resolvedRequestId, request);
    String deploymentId = candidate ? route.candidateDeploymentId() : route.baselineDeploymentId();
    String baseUrl = candidate ? route.candidateBaseUrl() : route.baselineBaseUrl();
    String completionId = "chatcmpl-" + UUID.randomUUID();
    Instant started = Instant.now();
    AtomicBoolean firstTokenEmitted = new AtomicBoolean();
    AtomicBoolean observed = new AtomicBoolean();
    return providers.provider(baseUrl).stream(
            request, new ProviderCallContext(resolvedRequestId, Duration.ofSeconds(30)))
        .doOnNext(
            event -> {
              if (event instanceof ProviderStreamEvent.Token) firstTokenEmitted.set(true);
            })
        .map(event -> toSse(completionId, request.model(), event))
        .doOnComplete(
            () ->
                observeStream(
                    observed,
                    route,
                    sampleId,
                    resolvedRequestId,
                    deploymentId,
                    candidate,
                    ObservedOutcome.SUCCESS,
                    200,
                    started))
        .doOnCancel(
            () ->
                observeStream(
                    observed,
                    route,
                    sampleId,
                    resolvedRequestId,
                    deploymentId,
                    candidate,
                    ObservedOutcome.CANCELLED,
                    499,
                    started))
        .doOnError(
            failure -> {
              FailureClassifier.Classification classification =
                  FailureClassifier.classify(failure, firstTokenEmitted.get());
              observeStream(
                  observed,
                  route,
                  sampleId,
                  resolvedRequestId,
                  deploymentId,
                  candidate,
                  observedOutcome(classification.kind()),
                  classification.statusCode(),
                  started);
            });
  }

  private RouteSnapshot requireRoute() {
    return snapshots
        .current()
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_SNAPSHOT_UNAVAILABLE"));
  }

  private UUID enqueueShadow(RouteSnapshot route, String requestId, ChatCompletionRequest request) {
    UUID sampleId = UUID.randomUUID();
    var event =
        new ShadowRequestedV1(
            1,
            UUID.randomUUID(),
            sampleId,
            requestId,
            route.rolloutId(),
            route.version(),
            route.candidateDeploymentId(),
            Instant.now(),
            request);
    offerEvent("aegis.shadow-requested.v1", event.eventId(), event);
    return sampleId;
  }

  private void enqueueBaseline(
      RouteSnapshot route,
      UUID sampleId,
      String requestId,
      ObservedOutcome outcome,
      int statusCode,
      Instant started) {
    var event =
        new BaselineObservedV1(
            1,
            UUID.randomUUID(),
            sampleId,
            requestId,
            route.rolloutId(),
            route.version(),
            route.baselineDeploymentId(),
            outcome,
            statusCode,
            Math.max(0, Duration.between(started, Instant.now()).toMillis()),
            Instant.now());
    offerEvent("aegis.baseline-observed.v1", event.eventId(), event);
  }

  private void enqueueServing(
      RouteSnapshot route,
      String requestId,
      String deploymentId,
      boolean candidate,
      ObservedOutcome outcome,
      int statusCode,
      Instant started) {
    var event =
        new ServingObservedV1(
            1,
            UUID.randomUUID(),
            requestId,
            route.rolloutId(),
            route.version(),
            deploymentId,
            candidate,
            outcome,
            statusCode,
            Math.max(0, Duration.between(started, Instant.now()).toMillis()),
            Instant.now());
    offerEvent("aegis.serving-observed.v1", event.eventId(), event);
  }

  private void offerEvent(String topic, UUID eventId, Object event) {
    try {
      byte[] bytes = eventMapper.writeValueAsBytes(event);
      shadowQueue.offer(new ShadowEnvelope(topic, eventId.toString(), bytes));
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
      // Telemetry is best-effort. Contract conformance belongs to producer tests and must never
      // turn an observation/serialization defect into a baseline response failure.
      shadowQueue.recordDrop(
          io.github.maoyouaa.aegisroute.gateway.shadow.ShadowDropReason.SERIALIZATION_ERROR);
    }
  }

  private void observeStream(
      AtomicBoolean observed,
      RouteSnapshot route,
      UUID sampleId,
      String requestId,
      String deploymentId,
      boolean candidate,
      ObservedOutcome outcome,
      int statusCode,
      Instant started) {
    if (!observed.compareAndSet(false, true)) return;
    enqueueServing(route, requestId, deploymentId, candidate, outcome, statusCode, started);
    if (!candidate) {
      enqueueBaseline(route, sampleId, requestId, outcome, statusCode, started);
    }
  }

  private ObservedOutcome observedOutcome(FailureClassifier.Kind kind) {
    return switch (kind) {
      case TIMEOUT -> ObservedOutcome.TIMEOUT;
      case CANCELLED -> ObservedOutcome.CANCELLED;
      case RESPONSE_STARTED, STREAM_FAILURE -> ObservedOutcome.STREAM_ERROR;
      default -> ObservedOutcome.HTTP_ERROR;
    };
  }

  private ServerSentEvent<Object> toSse(String id, String model, ProviderStreamEvent event) {
    ChatCompletionChunk.Choice choice =
        event instanceof ProviderStreamEvent.Token token
            ? new ChatCompletionChunk.Choice(
                0, new ChatCompletionChunk.Delta(null, token.content()), null)
            : new ChatCompletionChunk.Choice(
                0,
                new ChatCompletionChunk.Delta(null, null),
                ((ProviderStreamEvent.Completed) event).finishReason());
    return ServerSentEvent.builder(
            (Object)
                new ChatCompletionChunk(
                    id,
                    "chat.completion.chunk",
                    Instant.now().getEpochSecond(),
                    model,
                    List.of(choice)))
        .build();
  }
}
