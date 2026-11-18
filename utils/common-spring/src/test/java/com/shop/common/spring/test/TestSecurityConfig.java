package com.shop.common.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("JWT decoding is disabled in integration tests");
        };
    }
}
