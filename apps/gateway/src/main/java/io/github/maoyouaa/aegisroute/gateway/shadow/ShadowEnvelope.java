package io.github.maoyouaa.aegisroute.gateway.shadow;

public record ShadowEnvelope(String topic, String key, byte[] payload) {
  public ShadowEnvelope {
    payload = payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }

  public int size() {
    return payload.length;
  }
}
