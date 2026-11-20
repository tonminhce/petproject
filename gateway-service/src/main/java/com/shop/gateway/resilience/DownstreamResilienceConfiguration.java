package com.shop.gateway.resilience;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DownstreamResilienceConfiguration {

    private static final float SLOW_CALL_RATE_THRESHOLD = 100f;

    public static CircuitBreakerConfig circuitBreakerConfig(final CircuitBreakerProperties properties) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallRateThreshold(SLOW_CALL_RATE_THRESHOLD)
                .slowCallDurationThreshold(properties.slowCallDurationThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .build();
    }

    public static BulkheadConfig bulkheadConfig(final BulkheadProperties properties) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .build();
    }

    @Bean
    public CircuitBreakerRegistry downstreamCircuitBreakerRegistry(final CircuitBreakerProperties properties) {
        return CircuitBreakerRegistry.of(circuitBreakerConfig(properties));
    }

    @Bean
    public BulkheadRegistry downstreamBulkheadRegistry(final BulkheadProperties properties) {
        return BulkheadRegistry.of(bulkheadConfig(properties));
    }

    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetricsPublisher(final CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }

    @Bean
    public DownstreamResilienceFilter downstreamResilienceFilter(final CircuitBreakerProperties circuitBreakerProperties,
                                                                 final BulkheadProperties bulkheadProperties,
                                                                 final CircuitBreakerRegistry circuitBreakerRegistry,
                                                                 final BulkheadRegistry bulkheadRegistry,
                                                                 final GatewayErrorResponseWriter errorResponseWriter) {
        return new DownstreamResilienceFilter(circuitBreakerProperties, bulkheadProperties,
                circuitBreakerRegistry, bulkheadRegistry, errorResponseWriter);
    }
}
