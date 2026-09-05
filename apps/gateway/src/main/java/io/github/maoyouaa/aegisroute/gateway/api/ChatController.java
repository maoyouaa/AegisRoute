package io.github.maoyouaa.aegisroute.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.api.*;
import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import io.github.maoyouaa.aegisroute.domain.routing.StableSampler;
import io.github.maoyouaa.aegisroute.gateway.routing.RouteSnapshotStore;
import io.github.maoyouaa.aegisroute.gateway.shadow.*;
import io.github.maoyouaa.aegisroute.provider.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public final class ChatController {
  private final RouteSnapshotStore snapshots;
  private final BoundedShadowQueue shadowQueue;
  private final ObjectMapper eventMapper;
  private final OpenAiProviderFactory providers;
  private final GatewayAdmissionLedger admission;
  private final Duration deadline;
  private ServingMetrics metrics;

  @Autowired
  void servingMetrics(ServingMetrics metrics) {
    this.metrics = metrics;
  }

  public ChatController(
      RouteSnapshotStore snapshots,
      BoundedShadowQueue queue,
      ObjectMapper mapper,
      OpenAiProviderFactory providers) {
    this(
        snapshots,
        queue,
        mapper,
        providers,
        new GatewayAdmissionLedger(
            "gateway-test",
            snapshots,
            WebClient.builder(),
            "http://localhost:1",
            Duration.ofSeconds(35)),
        Duration.ofSeconds(30));
  }

  @Autowired
  public ChatController(
      RouteSnapshotStore snapshots,
      BoundedShadowQueue queue,
      ObjectMapper mapper,
      OpenAiProviderFactory providers,
      GatewayAdmissionLedger admission,
      @Value("${aegis.request-deadline:30s}") Duration deadline) {
    this.snapshots = snapshots;
    this.shadowQueue = queue;
    this.eventMapper = mapper;
    this.providers = providers;
    this.admission = admission;
    this.deadline = deadline;
  }

  @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
  Mono<ResponseEntity<?>> chat(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
    if (request.stream()) {
      return Mono.just(
          ResponseEntity.ok()
              .contentType(MediaType.TEXT_EVENT_STREAM)
              .body(stream(request, requestId)));
    }
    return complete(request, requestId);
  }

  Mono<ResponseEntity<?>> complete(ChatCompletionRequest request, String requestId) {
    Call call = begin(request, requestId);
    AtomicBoolean observed = new AtomicBoolean();
    return providers
        .provider(call.url())
        .complete(request, new ProviderCallContext(call.sample.requestId(), deadline))
        .<ResponseEntity<?>>map(
            response -> {
              observe(observed, call, ObservedOutcome.SUCCESS, 200);
              return ResponseEntity.ok()
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(
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
        .doOnCancel(() -> observe(observed, call, ObservedOutcome.CANCELLED, 499))
        .onErrorResume(
            failure -> {
              int status = status(failure);
              observe(
                  observed,
                  call,
                  status == 504 ? ObservedOutcome.TIMEOUT : ObservedOutcome.HTTP_ERROR,
                  status);
              return Mono.just(
                  ResponseEntity.status(status)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(new ApiError("PROVIDER_FAILURE", "Provider request failed")));
            });
  }

  Flux<ServerSentEvent<Object>> stream(ChatCompletionRequest request, String requestId) {
    Call call = begin(request, requestId);
    AtomicBoolean first = new AtomicBoolean();
    AtomicBoolean observed = new AtomicBoolean();
    String id = "chatcmpl-" + UUID.randomUUID();
    return providers.provider(call.url()).stream(
            request, new ProviderCallContext(call.sample.requestId(), deadline))
        .doOnNext(
            event -> {
              if (event instanceof ProviderStreamEvent.Token) first.set(true);
            })
        .map(event -> toSse(id, request.model(), event))
        .concatWithValues(ServerSentEvent.builder((Object) "[DONE]").build())
        .doOnComplete(() -> observe(observed, call, ObservedOutcome.SUCCESS, 200))
        .doOnCancel(() -> observe(observed, call, ObservedOutcome.CANCELLED, 499))
        .doOnError(
            failure ->
                observe(
                    observed,
                    call,
                    first.get()
                        ? ObservedOutcome.STREAM_ERROR
                        : status(failure) == 504
                            ? ObservedOutcome.TIMEOUT
                            : ObservedOutcome.HTTP_ERROR,
                    status(failure)));
  }

  private Call begin(ChatCompletionRequest request, String suppliedId) {
    RouteSnapshot route =
        snapshots
            .current()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_SNAPSHOT_UNAVAILABLE"));
    String requestId = suppliedId == null ? UUID.randomUUID().toString() : suppliedId;
    if (requestId.isBlank() || requestId.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request ID");
    }
    boolean candidate = StableSampler.selectsCandidate(requestId, route.candidateRatio());
    SampleIdentity sample = admission.admit(route, requestId, candidate);
    if (sample.shadowSelected()) {
      var shadow = new ShadowRequestedV2(2, UUID.randomUUID(), sample, request);
      offer("aegis.shadow-requested.v2", sample, shadow);
    }
    return new Call(sample, candidate, System.nanoTime());
  }

  private void observe(AtomicBoolean once, Call call, ObservedOutcome outcome, int status) {
    if (!once.compareAndSet(false, true)) return;
    if (metrics != null)
      metrics.record(call.candidate, outcome, System.nanoTime() - call.startedNanos);
    var sample = call.sample;
    var event =
        new ObservationV2(
            2,
            UUID.randomUUID(),
            sample,
            call.candidate ? ObservationV2.Kind.CANARY : ObservationV2.Kind.BASELINE,
            call.candidate
                ? sample.route().candidateDeploymentId()
                : sample.route().baselineDeploymentId(),
            outcome,
            status,
            Math.max(0, Duration.between(sample.admittedAt(), Instant.now()).toMillis()),
            Instant.now());
    offer("aegis.observation.v2", sample, event);
  }

  private void offer(String topic, SampleIdentity sample, Object event) {
    try {
      byte[] payload = eventMapper.writeValueAsBytes(event);
      if (!shadowQueue.offer(new ShadowEnvelope(topic, sample.key(), payload)))
        admission.dropped(sample);
    } catch (Exception invalid) {
      shadowQueue.recordDrop(ShadowDropReason.SERIALIZATION_ERROR);
      admission.dropped(sample);
    }
  }

  private static int status(Throwable failure) {
    if (failure instanceof ProviderException provider) {
      int status = provider.statusCode();
      return status == 504
          ? 504
          : status == 429 ? 429 : status >= 400 && status < 500 ? status : 502;
    }
    return failure instanceof java.util.concurrent.TimeoutException ? 504 : 502;
  }

  private ServerSentEvent<Object> toSse(String id, String model, ProviderStreamEvent event) {
    var choice =
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

  private record Call(SampleIdentity sample, boolean candidate, long startedNanos) {
    String url() {
      return candidate ? sample.route().candidateBaseUrl() : sample.route().baselineBaseUrl();
    }
  }
}
