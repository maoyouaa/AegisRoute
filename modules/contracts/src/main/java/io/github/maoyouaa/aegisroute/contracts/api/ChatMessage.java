package io.github.maoyouaa.aegisroute.contracts.api;

public record ChatMessage(String role, String content) {
  public ChatMessage {
    if (role == null || role.isBlank() || content == null) {
      throw new IllegalArgumentException("role and content are required");
    }
  }
}
