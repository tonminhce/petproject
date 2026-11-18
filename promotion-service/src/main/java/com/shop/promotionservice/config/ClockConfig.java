package com.shop.promotionservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public Clock systemUTC() {
        return Clock.systemUTC();
    }
}
