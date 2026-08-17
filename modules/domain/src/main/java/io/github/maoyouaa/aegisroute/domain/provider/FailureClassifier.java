package io.github.maoyouaa.aegisroute.domain.provider;

public final class FailureClassifier {
  private FailureClassifier() {}

  public static FailureKind classify(
      int statusCode,
      boolean timedOut,
      boolean cancelled,
      boolean responseStarted,
      boolean connectionFailure) {
    if (cancelled) return FailureKind.CANCELLED;
    if (responseStarted) return FailureKind.STREAM_ERROR;
    if (timedOut) return FailureKind.TIMEOUT;
    if (connectionFailure) return FailureKind.CONNECTION_ERROR;
    if (statusCode == 429) return FailureKind.RATE_LIMITED;
    if (statusCode >= 500 && statusCode <= 599) return FailureKind.PROVIDER_ERROR;
    if (statusCode >= 400 && statusCode <= 499) return FailureKind.CLIENT_ERROR;
    if (statusCode >= 100 && statusCode <= 399) return FailureKind.SUCCESS;
    return FailureKind.UNKNOWN;
  }

  public static boolean mayRetry(FailureKind kind, boolean firstTokenEmitted) {
    if (firstTokenEmitted) return false;
    return kind == FailureKind.RATE_LIMITED
        || kind == FailureKind.PROVIDER_ERROR
        || kind == FailureKind.TIMEOUT
        || kind == FailureKind.CONNECTION_ERROR;
  }
}
