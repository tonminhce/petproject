package com.shop.orderservice.exception;

import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamCircuitBreakerExceptionHandlerTest {

    private final DownstreamCircuitBreakerExceptionHandler handler = new DownstreamCircuitBreakerExceptionHandler();

    @Test
    @DisplayName("an open downstream circuit breaker is reported as a dependency failure")
    void anOpenCircuitBreakerIsReportedAsADependencyFailure() {
        var exception = CallNotPermittedException.createCallNotPermittedException(
            io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("inventoryService"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleCallNotPermitted(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
    }
}
