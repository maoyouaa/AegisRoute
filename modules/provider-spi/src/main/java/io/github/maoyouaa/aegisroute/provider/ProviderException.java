package io.github.maoyouaa.aegisroute.provider;

public final class ProviderException extends RuntimeException {
  private final int statusCode;
  private final boolean responseStarted;

  public ProviderException(int statusCode, boolean responseStarted, String message) {
    super(message);
    if (statusCode < 100 || statusCode > 599) {
      throw new IllegalArgumentException("statusCode must be between 100 and 599");
    }
    this.statusCode = statusCode;
    this.responseStarted = responseStarted;
  }

  public int statusCode() {
    return statusCode;
  }

  public boolean responseStarted() {
    return responseStarted;
  }
}
