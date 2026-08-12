package io.github.maoyouaa.aegisroute.contracts.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatCompletionChunk(
    String id, String object, long created, String model, List<Choice> choices) {
  public record Choice(
      int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {}

  public record Delta(String role, String content) {}
}
