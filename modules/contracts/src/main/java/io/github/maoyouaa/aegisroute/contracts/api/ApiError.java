package io.github.maoyouaa.aegisroute.contracts.api;

public record ApiError(ErrorBody error) {
  public ApiError(String code, String message) {
    this(new ErrorBody(code, message));
  }

  public record ErrorBody(String code, String message) {}
}
