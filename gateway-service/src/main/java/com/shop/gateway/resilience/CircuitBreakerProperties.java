package com.shop.gateway.resilience;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gateway.resilience.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,
        @DefaultValue("100") @Positive int slidingWindowSize,
        @DefaultValue("20") @Positive int minimumNumberOfCalls,
        @DefaultValue("10s") Duration waitDurationInOpenState,
        @DefaultValue("5") @Positive int permittedCallsInHalfOpenState,
        @DefaultValue("2s") Duration slowCallDurationThreshold
) {

    public CircuitBreakerProperties {
        if (minimumNumberOfCalls > slidingWindowSize) {
            throw new IllegalArgumentException("minimumNumberOfCalls must be <= slidingWindowSize");
        }
    }
}
