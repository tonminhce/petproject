package com.shop.shippingservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

@Configuration(proxyBeanMethods = false)
public class TestClockConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    public AtomicReference<Instant> testInstant() {
        return new AtomicReference<>(FIXED_INSTANT);
    }

    @Bean
    @Primary
    public Clock testClock(AtomicReference<Instant> testInstant) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return testInstant.get();
            }
        };
    }
}
