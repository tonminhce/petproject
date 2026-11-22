package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.exception.PaymentOperationRejectedException;
import com.shop.orderservice.exception.ProductLookupRejectedException;
import com.shop.orderservice.exception.PromotionApplicationRejectedException;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.exception.TaxCalculationRejectedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerIgnoreExceptionsTest {

    private static final int CALLS_WELL_BEYOND_THE_FAILURE_THRESHOLD = 25;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(orderServiceConfig()))
        .withConfiguration(UserConfigurations.of(
            CircuitBreakerConfiguration.class,
            BreakerAspectTestConfig.class))
        .withBean(BreakerProbe.class);

    @Test
    void aBusinessRefusalIsNeverRecordedAsAFailureAndNeverOpensTheBreaker() {
        runner.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            BreakerProbe probe = context.getBean(BreakerProbe.class);

            assertRefusalsStayIgnored(registry, probe, "inventoryService", probe::inventory,
                () -> new StockReservationFailedException(UUID.randomUUID(), null));
            assertRefusalsStayIgnored(registry, probe, "productService", probe::product,
                () -> new ProductLookupRejectedException(UUID.randomUUID()));
            assertRefusalsStayIgnored(registry, probe, "paymentService", probe::payment,
                () -> new PaymentOperationRejectedException(ErrorCode.PAYMENT_CURRENCY_MISMATCH, "VND", "USD"));
            assertRefusalsStayIgnored(registry, probe, "taxService", probe::tax,
                () -> new TaxCalculationRejectedException("tax refused"));
            assertRefusalsStayIgnored(registry, probe, "promotionService", probe::promotion,
                () -> new PromotionApplicationRejectedException("EXPIRED10"));
        });
    }

    @Test
    void aDownstreamServerFailureStillCountsAndOpensTheBreaker() {
        runner.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            BreakerProbe probe = context.getBean(BreakerProbe.class);
            probe.willThrow(BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment"));

            List<RuntimeException> observed = new ArrayList<>();
            for (int i = 0; i < CALLS_WELL_BEYOND_THE_FAILURE_THRESHOLD; i++) {
                try {
                    probe.payment();
                } catch (CallNotPermittedException | BusinessException ex) {
                    observed.add(ex);
                }
            }
            io.github.resilience4j.circuitbreaker.CircuitBreaker breaker = registry.circuitBreaker("paymentService");
            assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isGreaterThanOrEqualTo(20);
            assertThat(observed).filteredOn(ex -> ex instanceof CallNotPermittedException).isNotEmpty();
        });
    }

    private void assertRefusalsStayIgnored(CircuitBreakerRegistry registry, BreakerProbe probe,
            String breakerName, Supplier<Object> invocation, Supplier<RuntimeException> refusal) {
        Class<? extends RuntimeException> refusalType = refusal.get().getClass();
        probe.willThrow(refusal.get());
        List<RuntimeException> rejections = new ArrayList<>();
        for (int i = 0; i < CALLS_WELL_BEYOND_THE_FAILURE_THRESHOLD; i++) {
            try {
                invocation.get();
            } catch (RuntimeException ex) {
                rejections.add(ex);
            }
        }
        io.github.resilience4j.circuitbreaker.CircuitBreaker breaker = registry.circuitBreaker(breakerName);
        assertThat(rejections).hasSize(CALLS_WELL_BEYOND_THE_FAILURE_THRESHOLD);
        assertThat(rejections).allSatisfy(ex -> assertThat(ex).isInstanceOf(refusalType));
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
    }

    private static org.springframework.core.env.MapPropertySource orderServiceConfig() {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            Map<String, Object> merged = new LinkedHashMap<>();
            merge(loader.load("application-shared.yml", new ClassPathResource("application-shared.yml")), merged, false);
            merge(loader.load("application.yml", new ClassPathResource("application.yml")), merged, true);
            return new MapPropertySource("orderServiceBreakerTestConfig", merged);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void merge(List<PropertySource<?>> sources, Map<String, Object> target, boolean override) {
        for (PropertySource<?> source : sources) {
            if (source.getSource() instanceof Map<?, ?> values) {
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    Object value = entry.getValue() instanceof OriginTrackedValue tracked
                        ? tracked.getValue()
                        : entry.getValue();
                    if (override || !target.containsKey(entry.getKey())) {
                        target.put(String.valueOf(entry.getKey()), value);
                    }
                }
            }
        }
    }

    @Configuration
    @EnableConfigurationProperties(CircuitBreakerProperties.class)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class BreakerAspectTestConfig {
    }

    static class BreakerProbe {

        private Supplier<Object> behavior = () -> null;

        public void willThrow(RuntimeException exception) {
            this.behavior = () -> {
                throw exception;
            };
        }

        @CircuitBreaker(name = "inventoryService")
        public Object inventory() {
            return behavior.get();
        }

        @CircuitBreaker(name = "productService")
        public Object product() {
            return behavior.get();
        }

        @CircuitBreaker(name = "paymentService")
        public Object payment() {
            return behavior.get();
        }

        @CircuitBreaker(name = "taxService")
        public Object tax() {
            return behavior.get();
        }

        @CircuitBreaker(name = "promotionService")
        public Object promotion() {
            return behavior.get();
        }
    }
}
