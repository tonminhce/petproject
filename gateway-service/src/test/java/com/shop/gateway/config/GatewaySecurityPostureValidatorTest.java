package com.shop.gateway.config;

import com.shop.gateway.filter.AdminIpAllowlistProperties;
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

    private AdminIpAllowlistProperties allowlist(List<String> cidrs) {
        return new AdminIpAllowlistProperties(cidrs);
    }

    @Test
    void wildcardCorsOriginIsRejected() {
        var validator = new GatewaySecurityPostureValidator(properties(List.of("*")), rateLimit(1), allowlist(List.of()), "none", false);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowCredentials");
    }

    @Test
    void explicitCorsOriginListIsAccepted() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1), allowlist(List.of()), "none", false);
        validator.validate();
    }

    @Test
    void zeroTrustedProxyHopsWithForwardedHeaderStrategyIsRejected() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(0), allowlist(List.of()), "framework", false);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxy-hops");
    }

    @Test
    void zeroTrustedProxyHopsWithNoneStrategyIsAccepted() {

        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(0), allowlist(List.of()), "none", false);
        validator.validate();
    }

    @Test
    void oneTrustedProxyHopIsAccepted() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1), allowlist(List.of()), "none", false);
        validator.validate();
    }

    @Test
    void anEmptyAdminIpAllowlistIsRejectedWhenTheDeploymentRequiresIt() {

        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1),
                allowlist(List.of()), "none", true);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_IP_ALLOWLIST");
    }

    @Test
    void aConfiguredAdminIpAllowlistIsAcceptedWhenTheDeploymentRequiresIt() {
        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1),
                allowlist(List.of("203.0.113.0/24")), "none", true);
        validator.validate();
    }

    @Test
    void anEmptyAdminIpAllowlistIsOnlyWarnedAboutWhenTheDeploymentDoesNotRequireIt() {

        var validator = new GatewaySecurityPostureValidator(
                properties(List.of("http://localhost:3000")), rateLimit(1),
                allowlist(List.of()), "none", false);
        validator.validate();
    }
}
