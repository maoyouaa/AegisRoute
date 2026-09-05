package io.github.maoyouaa.aegisroute.contracts.events;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import java.util.UUID;

public record ShadowRequestedV2(
    int schemaVersion, UUID eventId, SampleIdentity sample, ChatCompletionRequest request) {
  public ShadowRequestedV2 {
    if (schemaVersion != 2
        || eventId == null
        || sample == null
        || request == null
        || !sample.shadowSelected()
        || !sample.route().shadows(sample.requestId(), false)) {
      throw new IllegalArgumentException("invalid shadow request");
    }
  }
}
