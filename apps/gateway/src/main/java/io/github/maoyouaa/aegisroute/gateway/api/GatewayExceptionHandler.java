package io.github.maoyouaa.aegisroute.gateway.api;

import io.github.maoyouaa.aegisroute.contracts.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public final class GatewayExceptionHandler {
  @ExceptionHandler(io.github.maoyouaa.aegisroute.provider.ProviderException.class)
  reactor.core.publisher.Mono<ResponseEntity<ApiError>> provider(
      io.github.maoyouaa.aegisroute.provider.ProviderException exception,
      org.springframework.web.server.ServerWebExchange exchange) {
    if (exchange.getResponse().isCommitted()) return reactor.core.publisher.Mono.error(exception);
    int status = exception.statusCode();
    if (status < 400 || status > 599 || status >= 500 && status != 504) status = 502;
    return reactor.core.publisher.Mono.just(
        ResponseEntity.status(status)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(new ApiError("PROVIDER_FAILURE", "Provider request failed")));
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
