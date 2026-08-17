package io.github.maoyouaa.aegisroute.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maoyouaa.aegisroute.contracts.api.ChatCompletionRequest;
import io.github.maoyouaa.aegisroute.contracts.api.ChatMessage;
import io.github.maoyouaa.aegisroute.domain.provider.FailureKind;
import io.github.maoyouaa.aegisroute.provider.ProviderCallContext;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderFailureClassifier;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenAiCompatibleProviderFailureContractTest {
  @Test
  void preservesHttp429BeforeStreamingAndDoesNotRetry() {
    assertHttpFailureIsPropagatedOnce(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void preservesHttp500BeforeStreamingAndDoesNotRetry() {
    assertHttpFailureIsPropagatedOnce(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void propagatesConnectionFailureWithoutRetry() {
    AtomicInteger attempts = new AtomicInteger();
    ClientHttpConnector connector =
        (method, uri, requestCallback) -> {
          attempts.incrementAndGet();
          return Mono.error(new ConnectException("connection refused"));
        };
    var provider =
        new OpenAiCompatibleProvider(
            WebClient.builder().clientConnector(connector), "http://provider.invalid");

    StepVerifier.create(provider.stream(request(), context()))
        .expectErrorSatisfies(
            failure -> {
              assertThat(failure)
                  .isInstanceOf(WebClientRequestException.class)
                  .hasRootCauseInstanceOf(ConnectException.class);
              assertThat(ProviderFailureClassifier.classify(failure, false))
                  .isEqualTo(
                      new ProviderFailureClassifier.Classification(
                          FailureKind.CONNECTION_ERROR, 502, true));
            })
        .verify();

    assertThat(attempts).hasValue(1);
  }

  private void assertHttpFailureIsPropagatedOnce(HttpStatus status) {
    AtomicInteger attempts = new AtomicInteger();
    var provider =
        new OpenAiCompatibleProvider(
            WebClient.builder()
                .exchangeFunction(
                    request -> {
                      attempts.incrementAndGet();
                      return Mono.just(ClientResponse.create(status).build());
                    }),
            "http://provider.invalid");

    StepVerifier.create(provider.stream(request(), context()))
        .expectErrorSatisfies(
            failure -> {
              assertThat(failure).isInstanceOf(ProviderException.class);
              ProviderException providerFailure = (ProviderException) failure;
              assertThat(providerFailure.statusCode()).isEqualTo(status.value());
              assertThat(providerFailure.responseStarted()).isFalse();
            })
        .verify();

    assertThat(attempts).hasValue(1);
  }

  private ChatCompletionRequest request() {
    return new ChatCompletionRequest(
        "support-assistant", List.of(new ChatMessage("user", "synthetic")), true, null);
  }

  private ProviderCallContext context() {
    return new ProviderCallContext("contract-request", Duration.ofSeconds(30));
  }
}
