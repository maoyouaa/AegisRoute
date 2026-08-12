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
    if (timedOut) return responseStarted ? FailureKind.STREAM_ERROR : FailureKind.TIMEOUT;
    if (connectionFailure) {
      return responseStarted ? FailureKind.STREAM_ERROR : FailureKind.CONNECTION_ERROR;
    }
    if (statusCode == 429) return FailureKind.RATE_LIMITED;
    if (statusCode >= 500) return FailureKind.PROVIDER_ERROR;
    if (statusCode >= 400) return FailureKind.CLIENT_ERROR;
    return FailureKind.SUCCESS;
  }

  public static boolean mayRetry(FailureKind kind, boolean firstTokenEmitted) {
    if (firstTokenEmitted) return false;
    return kind == FailureKind.RATE_LIMITED
        || kind == FailureKind.PROVIDER_ERROR
        || kind == FailureKind.TIMEOUT
        || kind == FailureKind.CONNECTION_ERROR;
  }
}
