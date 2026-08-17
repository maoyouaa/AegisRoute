package io.github.maoyouaa.aegisroute.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.maoyouaa.aegisroute.domain.provider.FailureKind;
import java.net.ConnectException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ProviderFailureClassifierTest {
  @Test
  void delegatesRetryEligibilityForFailuresBeforeTheFirstToken() {
    assertThat(
            ProviderFailureClassifier.classify(new ProviderException(429, false, "limited"), false))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.RATE_LIMITED, 429, true));
    assertThat(
            ProviderFailureClassifier.classify(
                new ProviderException(503, false, "unavailable"), false))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.PROVIDER_ERROR, 503, true));
    assertThat(ProviderFailureClassifier.classify(new ConnectException("refused"), false))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.CONNECTION_ERROR, 502, true));
    assertThat(ProviderFailureClassifier.classify(new TimeoutException("deadline"), false))
        .isEqualTo(new ProviderFailureClassifier.Classification(FailureKind.TIMEOUT, 504, true));
  }

  @Test
  void rejectsRetryForClientErrorsAndAmbiguousStartedResponses() {
    assertThat(
            ProviderFailureClassifier.classify(new ProviderException(400, false, "invalid"), false))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.CLIENT_ERROR, 400, false));
    assertThat(
            ProviderFailureClassifier.classify(new ProviderException(502, true, "started"), false))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.STREAM_ERROR, 502, false));
  }

  @Test
  void preservesEvidenceStatusWithoutRetryAfterTheFirstToken() {
    assertThat(ProviderFailureClassifier.classify(new ProviderException(503, false, "late"), true))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.STREAM_ERROR, 503, false));
    assertThat(ProviderFailureClassifier.classify(new TimeoutException("late deadline"), true))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.STREAM_ERROR, 504, false));
    assertThat(ProviderFailureClassifier.classify(new ConnectException("late disconnect"), true))
        .isEqualTo(
            new ProviderFailureClassifier.Classification(FailureKind.STREAM_ERROR, 502, false));
  }

  @Test
  void cancellationAndUnknownFailuresAreNeverRetryable() {
    assertThat(ProviderFailureClassifier.classify(new CancellationException("cancelled"), false))
        .isEqualTo(new ProviderFailureClassifier.Classification(FailureKind.CANCELLED, 499, false));
    assertThat(ProviderFailureClassifier.classify(new IllegalStateException("unknown"), false))
        .isEqualTo(new ProviderFailureClassifier.Classification(FailureKind.UNKNOWN, 502, false));
    assertThatThrownBy(() -> new ProviderException(999, false, "invalid status"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("statusCode must be between 100 and 599");
  }
}
