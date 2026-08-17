package io.github.maoyouaa.aegisroute.gateway.api;

import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import io.github.maoyouaa.aegisroute.provider.ProviderException;
import io.github.maoyouaa.aegisroute.provider.ProviderFailureClassifier;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public final class GatewayExceptionHandler {
  @ExceptionHandler(ProviderException.class)
  ResponseEntity<ApiError> providerFailure(ProviderException exception) {
    return providerFailure(exception.statusCode());
  }

  @ExceptionHandler({
    ConnectException.class,
    UnknownHostException.class,
    SocketTimeoutException.class,
    TimeoutException.class,
    WebClientRequestException.class
  })
  ResponseEntity<ApiError> providerTransportFailure(Throwable exception) {
    return providerFailure(ProviderFailureClassifier.classify(exception, false).statusCode());
  }

  private ResponseEntity<ApiError> providerFailure(int statusCode) {
    return ResponseEntity.status(statusCode)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiError("PROVIDER_FAILURE", "Upstream provider request failed"));
  }

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
