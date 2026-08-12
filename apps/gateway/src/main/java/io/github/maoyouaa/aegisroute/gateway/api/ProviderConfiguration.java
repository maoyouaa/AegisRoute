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
    return WebClient.builder();
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
