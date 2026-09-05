package io.github.maoyouaa.aegisroute.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ProviderConfiguration {
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  WebClient.Builder webClientBuilder() {
    // Docker service addresses may change after a stopped peer returns. Bound DNS staleness.
    var http =
        reactor.netty.http.client.HttpClient.create()
            .resolver(
                spec ->
                    spec.cacheMaxTimeToLive(java.time.Duration.ofSeconds(2))
                        .cacheNegativeTimeToLive(java.time.Duration.ofSeconds(1)))
            .disableRetry(true);
    return WebClient.builder()
        .clientConnector(
            new org.springframework.http.client.reactive.ReactorClientHttpConnector(http));
  }

  @Bean
  ObjectMapper eventObjectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  OpenAiProviderFactory openAiProviderFactory(WebClient.Builder builder) {
    return new OpenAiProviderFactory(builder);
  }
}
