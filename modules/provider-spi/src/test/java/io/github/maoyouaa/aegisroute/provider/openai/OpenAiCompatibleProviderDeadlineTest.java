package io.github.maoyouaa.aegisroute.provider.openai;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenAiCompatibleProviderDeadlineTest {
  @Test
  void completionBeforeDeadlineDoesNotWaitForTheDeadlineTimer() {
    StepVerifier.withVirtualTime(
            () ->
                OpenAiCompatibleProvider.enforceTotalDeadline(
                    Flux.just("token", "done"), Duration.ofSeconds(30)))
        .expectNext("token", "done")
        .verifyComplete();
  }

  @Test
  void totalDeadlineDoesNotResetAfterEachStreamToken() {
    StepVerifier.withVirtualTime(
            () ->
                OpenAiCompatibleProvider.enforceTotalDeadline(
                    Flux.interval(Duration.ofSeconds(1)).map(index -> "token-" + index),
                    Duration.ofMillis(2500)))
        .thenAwait(Duration.ofSeconds(1))
        .expectNext("token-0")
        .thenAwait(Duration.ofSeconds(1))
        .expectNext("token-1")
        .thenAwait(Duration.ofMillis(500))
        .expectError(TimeoutException.class)
        .verify();
  }
}
