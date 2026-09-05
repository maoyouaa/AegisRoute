package io.github.maoyouaa.aegisroute.provider.openai;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionChunk;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionResponse;
import io.github.maoyouaa.aegisroute.provider.InferenceProvider;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderResponse;
import io.github.maoyouaa.aegisroute.provider.ProviderStreamEvent;
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
    return Flux.defer(
        () -> {
          var token = new java.util.concurrent.atomic.AtomicBoolean();
          var finished = new java.util.concurrent.atomic.AtomicBoolean();
          var done = new java.util.concurrent.atomic.AtomicBoolean();
          var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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
                                  response.statusCode().value(),
                                  false,
                                  "Provider returned an error")))
                  .bodyToFlux(String.class)
                  .takeUntil(frame -> "[DONE]".equals(frame.trim()))
                  .concatMap(
                      frame -> {
                        if ("[DONE]".equals(frame.trim())) {
                          done.set(true);
                          return Flux.<ProviderStreamEvent>empty();
                        }
                        try {
                          var chunk = mapper.readValue(frame, ChatCompletionChunk.class);
                          java.util.List<ProviderStreamEvent> events = new java.util.ArrayList<>();
                          for (var choice : chunk.choices()) {
                            if (finished.get())
                              return Flux.error(
                                  new IllegalStateException("Data after stream finish"));
                            String content =
                                choice.delta() == null ? null : choice.delta().content();
                            if (content != null && !content.isEmpty()) {
                              token.set(true);
                              events.add(new ProviderStreamEvent.Token(content));
                            }
                            if (choice.finishReason() != null) {
                              finished.set(true);
                              events.add(new ProviderStreamEvent.Completed(choice.finishReason()));
                            }
                          }
                          return Flux.fromIterable(events);
                        } catch (Exception malformed) {
                          return Flux.error(
                              new IllegalStateException("Malformed provider stream", malformed));
                        }
                      })
                  .concatWith(
                      Flux.defer(
                          () ->
                              finished.get() && done.get()
                                  ? Flux.empty()
                                  : Flux.error(
                                      new IllegalStateException(
                                          "Provider stream ended without finish and DONE"))));
          return enforceTotalDeadline(source, context.deadline())
              .onErrorMap(
                  failure -> {
                    if (failure instanceof ProviderException provider) {
                      return new ProviderException(
                          provider.statusCode(), token.get(), "Provider stream failed");
                    }
                    return new ProviderException(
                        failure instanceof TimeoutException ? 504 : 502,
                        token.get(),
                        "Provider stream failed");
                  });
        });
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
