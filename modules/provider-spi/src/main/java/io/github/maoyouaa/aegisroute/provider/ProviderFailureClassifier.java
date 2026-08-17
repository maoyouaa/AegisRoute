package io.github.maoyouaa.aegisroute.provider;

import io.github.maoyouaa.aegisroute.domain.provider.FailureKind;
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
 * Adapts transport exceptions to the dependency-light Domain failure policy without performing a
 * retry. A retryable result means only that a separately bounded policy may consider the failure;
 * callers must still enforce one total deadline and one retry budget.
 */
public final class ProviderFailureClassifier {
  private ProviderFailureClassifier() {}

  public static Classification classify(Throwable failure, boolean firstTokenEmitted) {
    Objects.requireNonNull(failure, "failure");
    Throwable unwrapped = Exceptions.unwrap(failure);
    ProviderException providerFailure = findCause(unwrapped, ProviderException.class);
    int providerStatus = providerFailure == null ? 0 : providerFailure.statusCode();
    boolean cancelled = hasCause(unwrapped, CancellationException.class);
    boolean timedOut = hasTimeoutCause(unwrapped);
    boolean connectionFailure =
        hasCause(unwrapped, ConnectException.class)
            || hasCause(unwrapped, UnknownHostException.class);
    boolean responseStarted =
        firstTokenEmitted || (providerFailure != null && providerFailure.responseStarted());

    FailureKind kind =
        io.github.maoyouaa.aegisroute.domain.provider.FailureClassifier.classify(
            providerStatus, timedOut, cancelled, responseStarted, connectionFailure);
    int statusCode = evidenceStatus(providerStatus, timedOut, cancelled);
    boolean retryable =
        io.github.maoyouaa.aegisroute.domain.provider.FailureClassifier.mayRetry(
            kind, firstTokenEmitted);
    return new Classification(kind, statusCode, retryable);
  }

  private static int evidenceStatus(int providerStatus, boolean timedOut, boolean cancelled) {
    if (cancelled) return 499;
    if (timedOut) return 504;
    if (providerStatus >= 100 && providerStatus <= 599) return providerStatus;
    return 502;
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
      if (type.isInstance(cause)) return type.cast(cause);
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

  public record Classification(FailureKind kind, int statusCode, boolean retryable) {
    public Classification {
      Objects.requireNonNull(kind, "kind");
      if (statusCode < 100 || statusCode > 599) {
        throw new IllegalArgumentException("statusCode must be between 100 and 599");
      }
    }
  }
}
