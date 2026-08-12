package io.github.maoyouaa.aegisroute.provider;

public sealed interface ProviderStreamEvent
    permits ProviderStreamEvent.Token, ProviderStreamEvent.Completed {
  record Token(String content) implements ProviderStreamEvent {}

  record Completed(String finishReason) implements ProviderStreamEvent {}
}
