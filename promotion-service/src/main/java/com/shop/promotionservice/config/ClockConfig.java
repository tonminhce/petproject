package com.shop.promotionservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Injectable clock so time-dependent logic (reservation expiry, campaign
 * windows) is frozen in tests — {@code @MockitoBean Clock} or a fixed
 * {@code Clock.fixed(...)} replaces the real UTC clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemUTC() {
        return Clock.systemUTC();
    }
}
