package io.github.maoyouaa.aegisroute.provider;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InferenceProvider {
  Mono<ProviderResponse> complete(ChatCompletionRequest request, ProviderCallContext context);

  Flux<ProviderStreamEvent> stream(ChatCompletionRequest request, ProviderCallContext context);
}
