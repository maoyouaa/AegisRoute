package io.github.maoyouaa.aegisroute.provider;

import io.github.maoyouaa.aegisroute.provider.openai.OpenAiCompatibleProvider;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.reactive.function.client.WebClient;

public final class OpenAiProviderFactory {
  private final WebClient.Builder builder;
  private final ConcurrentHashMap<String, InferenceProvider> providers = new ConcurrentHashMap<>();

  public OpenAiProviderFactory(WebClient.Builder builder) {
    this.builder = builder;
  }

  public InferenceProvider provider(String baseUrl) {
    return providers.computeIfAbsent(
        baseUrl, url -> new OpenAiCompatibleProvider(builder.clone(), url));
  }
}
