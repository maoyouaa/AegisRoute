package io.github.maoyouaa.aegisroute.domain.provider;

public enum FailureKind {
  SUCCESS,
  RATE_LIMITED,
  PROVIDER_ERROR,
  CLIENT_ERROR,
  TIMEOUT,
  CANCELLED,
  STREAM_ERROR,
  CONNECTION_ERROR,
  UNKNOWN
}
