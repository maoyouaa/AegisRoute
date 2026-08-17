package io.github.maoyouaa.aegisroute.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenAiCompatibleProviderStreamContractTest {
  private static final String TOKEN_EVENT =
      "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
          + "\"model\":\"candidate-v1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":null,"
          + "\"content\":\"hello\"},\"finish_reason\":null}]}\n\n";
  private static final String COMPLETED_EVENT =
      "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
          + "\"model\":\"candidate-v1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":null,"
          + "\"content\":null},\"finish_reason\":\"stop\"}]}\n\n";

  @Test
  void decodesTokenAndCompletionEvents() {
    AtomicInteger attempts = new AtomicInteger();
    var provider = provider(attempts, Flux.just(buffer(TOKEN_EVENT + COMPLETED_EVENT)));

    StepVerifier.create(provider.stream(request(), context()))
        .expectNext(new ProviderStreamEvent.Token("hello"))
        .expectNext(new ProviderStreamEvent.Completed("stop"))
        .verifyComplete();

    assertThat(attempts).hasValue(1);
  }

  @Test
  void malformedSseJsonFailsWithoutRetry() {
    AtomicInteger attempts = new AtomicInteger();
    var provider = provider(attempts, Flux.just(buffer("data: not-json\n\n")));

    StepVerifier.create(provider.stream(request(), context()))
        .expectError(DecodingException.class)
        .verify();

    assertThat(attempts).hasValue(1);
  }

  @Test
  void disconnectBeforeFirstTokenFailsWithoutRetry() {
    AtomicInteger attempts = new AtomicInteger();
    var provider = provider(attempts, Flux.error(new IOException("disconnect before first token")));

    StepVerifier.create(provider.stream(request(), context()))
        .expectErrorSatisfies(
            failure -> assertThat(failure).hasMessageContaining("disconnect before first token"))
        .verify();

    assertThat(attempts).hasValue(1);
  }

  @Test
  void disconnectAfterFirstTokenDoesNotReplayTheToken() {
    AtomicInteger attempts = new AtomicInteger();
    var provider =
        provider(
            attempts,
            Flux.concat(
                Flux.just(buffer(TOKEN_EVENT)),
                Flux.error(new IOException("disconnect after first token"))));

    StepVerifier.create(provider.stream(request(), context()))
        .expectNext(new ProviderStreamEvent.Token("hello"))
        .expectErrorSatisfies(
            failure -> assertThat(failure).hasMessageContaining("disconnect after first token"))
        .verify();

    assertThat(attempts).hasValue(1);
  }

  @Test
  void clientCancellationCancelsTheProviderBody() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean cancelled = new AtomicBoolean();
    Flux<DataBuffer> body =
        Flux.concat(Flux.just(buffer(TOKEN_EVENT)), Flux.<DataBuffer>never())
            .doOnCancel(() -> cancelled.set(true));
    var provider = provider(attempts, body);

    StepVerifier.create(provider.stream(request(), context()))
        .expectNext(new ProviderStreamEvent.Token("hello"))
        .thenCancel()
        .verify();

    assertThat(attempts).hasValue(1);
    assertThat(cancelled).isTrue();
  }

  private OpenAiCompatibleProvider provider(AtomicInteger attempts, Flux<DataBuffer> responseBody) {
    return new OpenAiCompatibleProvider(
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  attempts.incrementAndGet();
                  return Mono.just(
                      ClientResponse.create(HttpStatus.OK)
                          .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                          .body(responseBody)
                          .build());
                }),
        "http://provider.invalid");
  }

  private DataBuffer buffer(String value) {
    return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
  }

  private ChatCompletionRequest request() {
    return new ChatCompletionRequest(
        "support-assistant", List.of(new ChatMessage("user", "synthetic")), true, null);
  }

  private ProviderCallContext context() {
    return new ProviderCallContext("stream-contract", Duration.ofSeconds(30));
  }
}
