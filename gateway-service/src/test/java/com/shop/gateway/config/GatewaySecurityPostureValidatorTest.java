package com.shop.gateway.config;

import com.shop.gateway.filter.AdminIpAllowlistProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewaySecurityPostureValidatorTest {

    private GatewayProperties properties(List<String> corsPatterns) {
        return new GatewayProperties("http://keycloak:8080/realms/ecommerce", corsPatterns, List.of("/actuator/health"));
    }

    private TrustedProxyProperties trustedProxy(final int hops, final List<String> cidrs) {
        return new TrustedProxyProperties(cidrs, hops);
    }

    private AdminIpAllowlistProperties allowlist(List<String> cidrs) {
        return new AdminIpAllowlistProperties(cidrs);
    }

    @Test
    void wildcardCorsOriginIsRejected() {
        var validator = validator(properties(List.of("*")), trustedProxy(0, List.of()),
                allowlist(List.of()), "none", false);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowCredentials");
    }

    @Test
    void explicitCorsOriginListIsAccepted() {
        validator(trustedProxy(0, List.of()), "none", false).validate();
    }

    @Test
    void forwardedHeaderStrategyIsRejected() {
        var validator = validator(trustedProxy(0, List.of()), "framework", false);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forward-headers-strategy");
    }

    @Test
    void zeroTrustedProxyHopsWithNoneStrategyIsAccepted() {
        validator(trustedProxy(0, List.of()), "none", false).validate();
    }

    @Test
    void trustedProxyHopsRequireATrustedProxyAllowlist() {
        var validator = validator(trustedProxy(1, List.of()), "none", false);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxy.cidrs");
    }

    @Test
    void oneTrustedProxyHopWithATrustedProxyAllowlistIsAccepted() {
        validator(trustedProxy(1, List.of("10.0.0.0/8")), "none", false).validate();
    }

    @Test
    void anEmptyAdminIpAllowlistIsRejectedWhenTheDeploymentRequiresIt() {
        var validator = validator(trustedProxy(1, List.of("10.0.0.0/8")), "none", true);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_IP_ALLOWLIST");
    }

    @Test
    void aConfiguredAdminIpAllowlistIsAcceptedWhenTheDeploymentRequiresIt() {
        validator(properties(List.of("http://localhost:3000")), trustedProxy(1, List.of("10.0.0.0/8")),
                allowlist(List.of("203.0.113.0/24")), "none", true).validate();
    }

    @Test
    void anEmptyAdminIpAllowlistIsOnlyWarnedAboutWhenTheDeploymentDoesNotRequireIt() {
        validator(trustedProxy(1, List.of("10.0.0.0/8")), "none", false).validate();
    }

    private GatewaySecurityPostureValidator validator(final TrustedProxyProperties trustedProxyProperties,
                                                      final String forwardHeadersStrategy,
                                                      final boolean requireAdminIpAllowlist) {
        return validator(properties(List.of("http://localhost:3000")), trustedProxyProperties,
                allowlist(List.of()), forwardHeadersStrategy, requireAdminIpAllowlist);
    }

    private GatewaySecurityPostureValidator validator(final GatewayProperties gatewayProperties,
                                                      final TrustedProxyProperties trustedProxyProperties,
                                                      final AdminIpAllowlistProperties adminIpAllowlistProperties,
                                                      final String forwardHeadersStrategy,
                                                      final boolean requireAdminIpAllowlist) {
        return new GatewaySecurityPostureValidator(
                gatewayProperties, trustedProxyProperties,
                adminIpAllowlistProperties, forwardHeadersStrategy, requireAdminIpAllowlist);
    }
}
