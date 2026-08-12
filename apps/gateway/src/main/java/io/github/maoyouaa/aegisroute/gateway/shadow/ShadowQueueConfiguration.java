package io.github.maoyouaa.aegisroute.gateway.shadow;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShadowQueueConfiguration {
  @Bean
  BoundedShadowQueue shadowQueue(
      @Value("${aegis.shadow.max-messages:1024}") int maxMessages,
      @Value("${aegis.shadow.max-bytes:16777216}") long maxBytes,
      MeterRegistry registry) {
    return new BoundedShadowQueue(maxMessages, maxBytes, registry);
  }
}
