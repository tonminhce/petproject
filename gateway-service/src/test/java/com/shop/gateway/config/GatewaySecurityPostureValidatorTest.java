package com.shop.gateway.config;

import com.shop.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewaySecurityPostureValidatorTest {

    private GatewayProperties properties(List<String> corsPatterns) {
        return new GatewayProperties("http://keycloak:8080/realms/ecommerce", corsPatterns, List.of("/actuator/health"));
    }

    private RateLimitProperties rateLimit(int trustedProxyHops) {
        return new RateLimitProperties(true, 100, 200, 1, trustedProxyHops);
    }

    @Test
    void wildcardCorsOriginIsRejected() {
        var validator = new GatewaySecurityPostureValidator(properties(List.of("*")), rateLimit(1), "none");
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowCredentials");
    }

    @Test
    void explicitCorsOriginListIsAccepted() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1), "none");
        validator.validate();
    }

    @Test
    void zeroTrustedProxyHopsWithForwardedHeaderStrategyIsRejected() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(0), "framework");
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxy-hops");
    }

    @Test
    void zeroTrustedProxyHopsWithNoneStrategyIsAccepted() {
        // The gateway resolves the client IP itself from the raw
        // X-Forwarded-For chain; with no trusted proxy the socket address is
        // the only meaningful value.
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(0), "none");
        validator.validate();
    }

    @Test
    void oneTrustedProxyHopIsAccepted() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1), "none");
        validator.validate();
    }
}
