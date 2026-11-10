package com.shop.gateway.config;

import com.shop.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-031 / F-033 — the gateway refuses to start with a silent security
 * misconfiguration.
 *
 * <ul>
 *   <li>F-033: a wildcard CORS origin pattern combined with the gateway's
 *       {@code allowCredentials(true)} lets any website issue credentialed
 *       cross-origin requests to the edge.</li>
 *   <li>F-031: {@code trusted-proxy-hops=0} with a forwarded-header strategy
 *       other than {@code none} makes the client IP resolve from a
 *       caller-controlled {@code X-Forwarded-For} header, bypassing both the
 *       admin IP allowlist and the per-IP rate limit.</li>
 * </ul>
 */
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
