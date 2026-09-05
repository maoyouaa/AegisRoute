package io.github.maoyouaa.aegisroute.mockprovider;

import io.github.maoyouaa.aegisroute.contracts.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;

@RestController
@EnableConfigurationProperties(MockProviderProperties.class)
public final class MockProviderController {
  private final MockProviderProperties properties;
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong cancelled = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private final ConcurrentHashMap<String, AtomicLong> calls = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> cancellations = new ConcurrentHashMap<>();
  private volatile Fault fault;

  public MockProviderController(MockProviderProperties properties) {
    this.properties = properties;
    this.fault = new Fault("normal", properties.failEvery(), properties.latency().toMillis());
  }

  @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<?>> chat(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
    String id = requestId == null ? UUID.randomUUID().toString() : requestId;
    long sequence = requests.incrementAndGet();
    if (properties.faultControlsEnabled() && id.length() <= 200 && calls.size() < 8192)
      calls.computeIfAbsent(id, key -> new AtomicLong()).incrementAndGet();
    Fault selected = fault;
    String mode =
        selected.failEvery() > 0 && sequence % selected.failEvery() == 0
            ? "http-500"
            : selected.mode();
    Duration delay = Duration.ofMillis(selected.latencyMs());
    if (request.stream()) {
      Flux<ServerSentEvent<Object>> body =
          stream(request, mode, delay).doFinally(signal -> finished(id, signal));
      return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body));
    }
    if (mode.startsWith("http-")) {
      int status = Integer.parseInt(mode.substring(5));
      return Mono.delay(delay)
          .<ResponseEntity<?>>map(ignored -> ResponseEntity.status(status).build())
          .doFinally(signal -> finished(id, signal));
    }
    var response =
        new ChatCompletionResponse(
            "chatcmpl-" + UUID.randomUUID(),
            "chat.completion",
            Instant.now().getEpochSecond(),
            request.model(),
            List.of(
                new ChatCompletionResponse.Choice(
                    0, new ChatMessage("assistant", properties.responseText()), "stop")),
            new ChatCompletionResponse.Usage(1, 1, 2));
    return Mono.delay(mode.equals("timeout") ? Duration.ofSeconds(30) : delay)
        .<ResponseEntity<?>>map(
            ignored -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response))
        .doFinally(signal -> finished(id, signal));
  }

  private Flux<ServerSentEvent<Object>> stream(
      ChatCompletionRequest request, String mode, Duration delay) {
    if (mode.startsWith("http-"))
      return Flux.error(
          new ResponseStatusException(
              HttpStatusCode.valueOf(Integer.parseInt(mode.substring(5))),
              "Synthetic provider error"));
    if (mode.equals("disconnect-before-token"))
      return Flux.error(
          new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Synthetic pre-token failure"));
    if (mode.equals("timeout")) return Mono.delay(Duration.ofSeconds(30)).thenMany(Flux.empty());
    if (mode.equals("empty")) return Flux.empty();
    if (mode.equals("malformed"))
      return Flux.just(ServerSentEvent.builder((Object) "{invalid synthetic json").build());
    String id = "chatcmpl-" + UUID.randomUUID();
    var token = frame(id, request.model(), properties.responseText(), null);
    var finish = frame(id, request.model(), null, "stop");
    var done = ServerSentEvent.builder((Object) "[DONE]").build();
    if (mode.equals("disconnect-after-token")) {
      return Flux.just(token)
          .delayElements(delay)
          .concatWith(
              Mono.delay(Duration.ofMillis(100))
                  .thenMany(
                      Flux.error(new IllegalStateException("synthetic post-token disconnect"))));
    }
    if (mode.equals("long-stream")) {
      return Flux.interval(Duration.ofMillis(50))
          .take(200)
          .map(ignored -> token)
          .concatWithValues(finish, done);
    }
    return Flux.just(token, finish, done).delayElements(delay);
  }

  private ServerSentEvent<Object> frame(String id, String model, String text, String finish) {
    return ServerSentEvent.builder(
            (Object)
                new ChatCompletionChunk(
                    id,
                    "chat.completion.chunk",
                    Instant.now().getEpochSecond(),
                    model,
                    List.of(
                        new ChatCompletionChunk.Choice(
                            0, new ChatCompletionChunk.Delta(null, text), finish))))
        .build();
  }

  private void finished(String id, SignalType signal) {
    if (signal == SignalType.CANCEL) {
      cancelled.incrementAndGet();
      if (properties.faultControlsEnabled() && id.length() <= 200 && cancellations.size() < 8192)
        cancellations.computeIfAbsent(id, key -> new AtomicLong()).incrementAndGet();
    } else if (signal == SignalType.ON_COMPLETE) completed.incrementAndGet();
  }

  @PostMapping("/internal/faults")
  public Fault configure(@RequestBody Fault next) {
    requireControls();
    fault = next;
    return next;
  }

  @GetMapping("/internal/stats")
  public Map<String, Object> stats() {
    requireControls();
    Map<String, Long> byRequest = new TreeMap<>();
    calls.forEach((key, value) -> byRequest.put(key, value.get()));
    Map<String, Long> cancelledRequests = new TreeMap<>();
    cancellations.forEach((key, value) -> cancelledRequests.put(key, value.get()));
    return Map.of(
        "name",
        properties.name(),
        "requests",
        requests.get(),
        "completed",
        completed.get(),
        "cancelled",
        cancelled.get(),
        "calls",
        byRequest,
        "cancelledRequests",
        cancelledRequests,
        "fault",
        fault);
  }

  private void requireControls() {
    if (!properties.faultControlsEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public record Fault(String mode, int failEvery, long latencyMs) {
    public Fault {
      if (mode == null
          || !Set.of(
                  "normal",
                  "http-429",
                  "http-400",
                  "http-500",
                  "timeout",
                  "disconnect-before-token",
                  "disconnect-after-token",
                  "malformed",
                  "empty",
                  "long-stream")
              .contains(mode)
          || failEvery < 0
          || failEvery > 100000
          || latencyMs < 0
          || latencyMs > 30000) {
        throw new IllegalArgumentException("Invalid synthetic fault configuration");
      }
    }
  }
}
