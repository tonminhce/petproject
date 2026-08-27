package com.shop.common.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test-only {@link JwtDecoder} stub.
 *
 * <p>Replaces the production decoder (which fetches JWKS from Keycloak at
 * startup) so integration tests can boot the full Spring context without a
 * real identity provider. Shipped in {@code common-spring}'s test-jar so
 * every service can {@code @Import} it without copying the class.
 *
 * <p>Usage:
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestSecurityConfig.class)
 * class MyIntegrationTest { ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("JWT decoding is disabled in integration tests");
        };
    }
}
