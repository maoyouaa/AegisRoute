package io.github.maoyouaa.aegisroute.contracts.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatCompletionResponse(
    String id, String object, long created, String model, List<Choice> choices, Usage usage) {
  public record Choice(
      int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {}

  public record Usage(
      @JsonProperty("prompt_tokens") int promptTokens,
      @JsonProperty("completion_tokens") int completionTokens,
      @JsonProperty("total_tokens") int totalTokens) {}
}
