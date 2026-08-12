package io.github.maoyouaa.aegisroute.domain.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureClassifierTest {
  @Test
  void neverRetriesAfterFirstToken() {
    assertThat(FailureClassifier.mayRetry(FailureKind.CONNECTION_ERROR, true)).isFalse();
    assertThat(FailureClassifier.mayRetry(FailureKind.PROVIDER_ERROR, false)).isTrue();
  }

  @Test
  void classifiesPostTokenDisconnectAsStreamError() {
    assertThat(FailureClassifier.classify(0, false, false, true, true))
        .isEqualTo(FailureKind.STREAM_ERROR);
  }
}
