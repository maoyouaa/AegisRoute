package io.github.maoyouaa.aegisroute.mockprovider;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionChunk;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionResponse;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@EnableConfigurationProperties(MockProviderProperties.class)
public final class MockProviderController {
  private final MockProviderProperties properties;
  private final AtomicLong requests = new AtomicLong();

  public MockProviderController(MockProviderProperties properties) {
    this.properties = properties;
  }

  @PostMapping(
      path = "/v1/chat/completions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE,
      headers = "Accept!=text/event-stream")
  public Mono<ResponseEntity<ChatCompletionResponse>> complete(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Aegis-Fault", required = false) String fault) {
    long sequence = requests.incrementAndGet();
    if (shouldFail(sequence, fault)) {
      return Mono.delay(properties.latency())
          .map(ignored -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
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
    return Mono.delay(properties.latency()).map(ignored -> ResponseEntity.ok(response));
  }

  @PostMapping(
      path = "/v1/chat/completions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE,
      headers = "Accept=text/event-stream")
  public Flux<ServerSentEvent<ChatCompletionChunk>> stream(
      @RequestBody ChatCompletionRequest request,
      @RequestHeader(name = "X-Aegis-Fault", required = false) String fault) {
    long sequence = requests.incrementAndGet();
    if ("disconnect-before-token".equals(fault)) {
      return Flux.error(new IllegalStateException("synthetic pre-token disconnect"));
    }
    if (shouldFail(sequence, fault)) {
      return Flux.error(new MockHttpException());
    }
    String id = "chatcmpl-" + UUID.randomUUID();
    var token =
        new ChatCompletionChunk(
            id,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            request.model(),
            List.of(
                new ChatCompletionChunk.Choice(
                    0,
                    new ChatCompletionChunk.Delta("assistant", properties.responseText()),
                    null)));
    var complete =
        new ChatCompletionChunk(
            id,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            request.model(),
            List.of(
                new ChatCompletionChunk.Choice(
                    0, new ChatCompletionChunk.Delta(null, null), "stop")));
    Flux<ServerSentEvent<ChatCompletionChunk>> normal =
        Flux.just(token, complete)
            .delayElements(properties.latency())
            .map(value -> ServerSentEvent.builder(value).build());
    return "disconnect-after-token".equals(fault)
        ? normal
            .take(1)
            .concatWith(Flux.error(new IllegalStateException("synthetic post-token disconnect")))
        : normal;
  }

  private boolean shouldFail(long sequence, String fault) {
    return "http-500".equals(fault)
        || (properties.failEvery() > 0 && sequence % properties.failEvery() == 0);
  }

  private static final class MockHttpException extends RuntimeException {}
}
