package io.github.maoyouaa.aegisroute.provider.openai;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionChunk;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionResponse;
import io.github.maoyouaa.aegisroute.provider.InferenceProvider;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderResponse;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class OpenAiCompatibleProvider implements InferenceProvider {
  private final WebClient webClient;

  public OpenAiCompatibleProvider(WebClient.Builder builder, String baseUrl) {
    this.webClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public Mono<ProviderResponse> complete(
      ChatCompletionRequest request, ProviderCallContext context) {
    return webClient
        .post()
        .uri("/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Request-Id", context.requestId())
        .bodyValue(request)
        .retrieve()
        .onStatus(
            status -> status.isError(),
            response ->
                Mono.error(
                    new ProviderException(
                        response.statusCode().value(), false, "Provider returned an error")))
        .bodyToMono(ChatCompletionResponse.class)
        .timeout(context.deadline())
        .map(
            response ->
                new ProviderResponse(
                    200,
                    response.choices().isEmpty()
                        ? ""
                        : response.choices().getFirst().message().content(),
                    response.model()));
  }

  @Override
  public Flux<ProviderStreamEvent> stream(
      ChatCompletionRequest request, ProviderCallContext context) {
    Flux<ProviderStreamEvent> source =
        webClient
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header("X-Request-Id", context.requestId())
            .bodyValue(request)
            .retrieve()
            .onStatus(
                status -> status.isError(),
                response ->
                    Mono.error(
                        new ProviderException(
                            response.statusCode().value(), false, "Provider returned an error")))
            .bodyToFlux(ChatCompletionChunk.class)
            .flatMapIterable(
                chunk ->
                    chunk.choices().stream()
                        .map(
                            choice -> {
                              if (choice.finishReason() != null) {
                                return (ProviderStreamEvent)
                                    new ProviderStreamEvent.Completed(choice.finishReason());
                              }
                              String content =
                                  choice.delta() == null ? null : choice.delta().content();
                              return content == null
                                  ? null
                                  : (ProviderStreamEvent) new ProviderStreamEvent.Token(content);
                            })
                        .filter(Objects::nonNull)
                        .toList());
    return enforceTotalDeadline(source, context.deadline());
  }

  static <T> Flux<T> enforceTotalDeadline(Flux<T> source, java.time.Duration deadline) {
    Flux<reactor.core.publisher.Signal<T>> deadlineSignal =
        Mono.delay(deadline)
            .map(
                ignored ->
                    reactor.core.publisher.Signal.<T>error(
                        new TimeoutException("Provider stream exceeded its total deadline")))
            .flux();
    return Flux.merge(source.materialize(), deadlineSignal)
        .takeUntil(signal -> signal.isOnComplete() || signal.isOnError())
        .dematerialize();
  }
}
