package com.shop.orderservice.exception;

import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = Object.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DownstreamCircuitBreakerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DownstreamCircuitBreakerExceptionHandler.class);

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiResponse<Void>> handleCallNotPermitted(
        CallNotPermittedException exception) {

        log.warn("A downstream call was not permitted because its circuit breaker is open: {}", exception.getMessage());
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "A downstream dependency is unavailable", null));
    }

    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ApiResponse<Void>> handleBulkheadFull(BulkheadFullException exception) {

        log.warn("A downstream call was rejected because its bulkhead is full: {}", exception.getMessage());
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE.getCode(), "A downstream dependency is unavailable", null));
    }
}
