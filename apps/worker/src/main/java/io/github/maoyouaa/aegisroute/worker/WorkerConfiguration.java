package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WorkerConfiguration {
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
  EventSchemaValidator eventSchemaValidator(ObjectMapper objectMapper) {
    return new EventSchemaValidator(objectMapper);
  }

  @Bean
  OpenAiProviderFactory providers(WebClient.Builder builder) {
    return new OpenAiProviderFactory(builder);
  }

  @Bean
  ResultPairingStore pairingStore() {
    return new ResultPairingStore(Duration.ofMinutes(10), Clock.systemUTC());
  }

  @Bean
  ShadowEligibilityEvaluator shadowEligibilityEvaluator() {
    return new ShadowEligibilityEvaluator(10, 0.50);
  }
}
