package io.github.maoyouaa.aegisroute.control.api;

import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException exception) {
    return ResponseEntity.status(exception.status())
        .body(new ApiError(exception.code(), exception.getMessage()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException exception) {
    if ("If-Match".equalsIgnoreCase(exception.getHeaderName())) {
      return ResponseEntity.status(428)
          .body(new ApiError("PRECONDITION_REQUIRED", "If-Match is required"));
    }
    return ResponseEntity.badRequest()
        .body(new ApiError("MISSING_REQUIRED_HEADER", exception.getHeaderName() + " is required"));
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> malformed(
      org.springframework.http.converter.HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", Map.of("code", "INVALID_REQUEST", "message", "Invalid JSON body")));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_REQUEST", exception.getMessage()));
  }
}
