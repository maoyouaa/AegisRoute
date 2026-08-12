package io.github.maoyouaa.aegisroute.gateway.shadow;

public enum ShadowDropReason {
  LOCAL_QUEUE_FULL("local_queue_full"),
  PAYLOAD_TOO_LARGE("payload_too_large"),
  SERIALIZATION_ERROR("serialization_error"),
  PUBLISHER_TIMEOUT("publisher_timeout"),
  BROKER_UNAVAILABLE("broker_unavailable"),
  SHUTDOWN("shutdown");

  private final String metricValue;

  ShadowDropReason(String metricValue) {
    this.metricValue = metricValue;
  }

  public String metricValue() {
    return metricValue;
  }
}
