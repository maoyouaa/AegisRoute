package io.github.maoyouaa.aegisroute.mockprovider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aegis.mock")
public record MockProviderProperties(
    String name, int failEvery, Duration latency, String responseText) {
  public MockProviderProperties {
    if (name == null) name = "baseline";
    if (latency == null) latency = Duration.ofMillis(5);
    if (responseText == null) responseText = "Synthetic response from " + name;
  }
}
