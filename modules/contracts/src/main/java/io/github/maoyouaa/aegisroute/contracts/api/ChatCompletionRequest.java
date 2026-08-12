package io.github.maoyouaa.aegisroute.contracts.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatCompletionRequest(
    String model,
    List<ChatMessage> messages,
    boolean stream,
    @JsonProperty("max_tokens") Integer maxTokens) {
  public ChatCompletionRequest {
    if (model == null || model.isBlank() || messages == null || messages.isEmpty()) {
      throw new IllegalArgumentException("model and at least one message are required");
    }
    messages = List.copyOf(messages);
  }
}
