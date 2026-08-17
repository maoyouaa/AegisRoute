package io.github.maoyouaa.aegisroute.provider;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import reactor.core.Exceptions;

/**
 * Classifies provider failures without performing retries. A retryable result means only that the
 * failure happened before observable stream output and may be eligible for a separately bounded
 * policy; callers must still enforce one total deadline and one retry budget.
 */
public final class FailureClassifier {
  private FailureClassifier() {}

  public static Classification classify(Throwable failure, boolean firstTokenEmitted) {
    Objects.requireNonNull(failure, "failure");
    Throwable unwrapped = Exceptions.unwrap(failure);

    if (hasCause(unwrapped, CancellationException.class)) {
      return new Classification(Kind.CANCELLED, 499, false);
    }

    ProviderException providerFailure = findCause(unwrapped, ProviderException.class);
    int providerStatus =
        providerFailure == null ? 502 : normalizedStatus(providerFailure.statusCode());

    if (firstTokenEmitted) {
      return new Classification(Kind.STREAM_FAILURE, providerStatus, false);
    }

    if (providerFailure != null) {
      if (providerFailure.responseStarted()) {
        return new Classification(Kind.RESPONSE_STARTED, providerStatus, false);
      }
      if (providerStatus == 429) {
        return new Classification(Kind.THROTTLED, providerStatus, true);
      }
      if (providerStatus >= 500) {
        return new Classification(Kind.UPSTREAM_SERVER_ERROR, providerStatus, true);
      }
      if (providerStatus >= 400) {
        return new Classification(Kind.UPSTREAM_CLIENT_ERROR, providerStatus, false);
      }
    }

    if (hasTimeoutCause(unwrapped)) {
      return new Classification(Kind.TIMEOUT, 504, true);
    }
    if (hasCause(unwrapped, ConnectException.class)
        || hasCause(unwrapped, UnknownHostException.class)) {
      return new Classification(Kind.CONNECTION_FAILURE, 502, true);
    }
    return new Classification(Kind.UNKNOWN, providerStatus, false);
  }

  private static int normalizedStatus(int statusCode) {
    return statusCode >= 100 && statusCode <= 599 ? statusCode : 502;
  }

  private static boolean hasTimeoutCause(Throwable failure) {
    for (Throwable cause : causes(failure)) {
      if (cause instanceof TimeoutException
          || cause instanceof SocketTimeoutException
          || cause.getClass().getSimpleName().endsWith("TimeoutException")) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Throwable> boolean hasCause(Throwable failure, Class<T> type) {
    return findCause(failure, type) != null;
  }

  private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
    for (Throwable cause : causes(failure)) {
      if (type.isInstance(cause)) {
        return type.cast(cause);
      }
    }
    return null;
  }

  private static Iterable<Throwable> causes(Throwable failure) {
    java.util.List<Throwable> chain = new java.util.ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    while (current != null && seen.add(current)) {
      chain.add(current);
      current = current.getCause();
    }
    return chain;
  }

  public enum Kind {
    THROTTLED,
    UPSTREAM_SERVER_ERROR,
    UPSTREAM_CLIENT_ERROR,
    CONNECTION_FAILURE,
    TIMEOUT,
    RESPONSE_STARTED,
    STREAM_FAILURE,
    CANCELLED,
    UNKNOWN
  }

  public record Classification(Kind kind, int statusCode, boolean retryable) {
    public Classification {
      Objects.requireNonNull(kind, "kind");
      if (statusCode < 100 || statusCode > 599) {
        throw new IllegalArgumentException("statusCode must be between 100 and 599");
      }
    }
  }
}
