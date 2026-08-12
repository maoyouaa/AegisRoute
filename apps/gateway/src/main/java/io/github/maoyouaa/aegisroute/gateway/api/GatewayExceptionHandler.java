package io.github.maoyouaa.aegisroute.gateway.api;

import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public final class GatewayExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
    if (exception.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE
        && "ROUTE_SNAPSHOT_UNAVAILABLE".equals(exception.getReason())) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new ApiError("ROUTE_SNAPSHOT_UNAVAILABLE", "No valid Route Snapshot is available"));
    }
    String reason = exception.getReason() == null ? "Request failed" : exception.getReason();
    return ResponseEntity.status(exception.getStatusCode())
        .body(new ApiError("REQUEST_FAILED", reason));
  }
}
