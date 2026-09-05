package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import io.github.maoyouaa.aegisroute.provider.OpenAiProviderFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WorkerConfiguration {
  @Bean(destroyMethod = "close")
  DurableEvidenceStore durableStore(
      ObjectMapper mapper,
      @org.springframework.beans.factory.annotation.Value(
              "${aegis.worker-store:./build/worker-state/evidence.sqlite}")
          String file) {
    return new DurableEvidenceStore(java.nio.file.Path.of(file), mapper);
  }

  @Bean
  org.springframework.kafka.listener.DefaultErrorHandler durableRecordErrorHandler() {
    return new org.springframework.kafka.listener.DefaultErrorHandler(
        new org.springframework.util.backoff.FixedBackOff(
            1000, org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS));
  }

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
  EventSchemaValidator eventSchemaValidator(ObjectMapper objectMapper) {
    return new EventSchemaValidator(objectMapper);
  }

  @Bean
  OpenAiProviderFactory providers(WebClient.Builder builder) {
    return new OpenAiProviderFactory(builder);
  }
}
