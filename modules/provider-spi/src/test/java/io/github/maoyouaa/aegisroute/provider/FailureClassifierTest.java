package io.github.maoyouaa.aegisroute.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class FailureClassifierTest {
  @Test
  void classifiesRetryableFailuresBeforeTheFirstToken() {
    assertThat(FailureClassifier.classify(new ProviderException(429, false, "limited"), false))
        .isEqualTo(
            new FailureClassifier.Classification(FailureClassifier.Kind.THROTTLED, 429, true));
    assertThat(FailureClassifier.classify(new ProviderException(503, false, "unavailable"), false))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.UPSTREAM_SERVER_ERROR, 503, true));
    assertThat(FailureClassifier.classify(new ConnectException("refused"), false))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.CONNECTION_FAILURE, 502, true));
    assertThat(FailureClassifier.classify(new TimeoutException("deadline"), false))
        .isEqualTo(new FailureClassifier.Classification(FailureClassifier.Kind.TIMEOUT, 504, true));
  }

  @Test
  void rejectsRetryForClientErrorsAndAmbiguousStartedResponses() {
    assertThat(FailureClassifier.classify(new ProviderException(400, false, "invalid"), false))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.UPSTREAM_CLIENT_ERROR, 400, false));
    assertThat(FailureClassifier.classify(new ProviderException(502, true, "started"), false))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.RESPONSE_STARTED, 502, false));
  }

  @Test
  void anyFailureAfterTheFirstTokenIsNeverRetryable() {
    assertThat(FailureClassifier.classify(new ProviderException(503, false, "late"), true))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.STREAM_FAILURE, 503, false));
    assertThat(FailureClassifier.classify(new ConnectException("late disconnect"), true))
        .isEqualTo(
            new FailureClassifier.Classification(
                FailureClassifier.Kind.STREAM_FAILURE, 502, false));
  }

  @Test
  void cancellationAndUnknownFailuresAreNeverRetryable() {
    assertThat(FailureClassifier.classify(new CancellationException("cancelled"), false))
        .isEqualTo(
            new FailureClassifier.Classification(FailureClassifier.Kind.CANCELLED, 499, false));
    assertThat(FailureClassifier.classify(new IllegalStateException("unknown"), false))
        .isEqualTo(
            new FailureClassifier.Classification(FailureClassifier.Kind.UNKNOWN, 502, false));
  }
}
