package com.shop.common.spring.web.util;

import com.shop.common.spring.config.TrustedProxyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpTrustPostureValidatorTest {

    @Test
    @DisplayName("a framework that rewrites the remote address from a caller header is refused")
    void aFrameworkThatRewritesTheRemoteAddressIsRefused() {
        ClientIpTrustPostureValidator validator = new ClientIpTrustPostureValidator(
                "framework", new TrustedProxyProperties(List.of(), 0));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.forward-headers-strategy");
    }

    @Test
    @DisplayName("trusted hops without a trusted proxy allow-list are refused")
    void trustedHopsWithoutATrustedProxyAllowListAreRefused() {
        ClientIpTrustPostureValidator validator = new ClientIpTrustPostureValidator(
                "none", new TrustedProxyProperties(List.of(), 2));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.web.trusted-proxy.cidrs is empty");
    }

    @Test
    @DisplayName("the transport peer only posture is accepted")
    void theTransportPeerOnlyPostureIsAccepted() {
        ClientIpTrustPostureValidator validator = new ClientIpTrustPostureValidator(
                "none", new TrustedProxyProperties(List.of(), 0));

        validator.validate();
    }

    @Test
    @DisplayName("a declared trusted proxy allow-list is accepted")
    void aDeclaredTrustedProxyAllowListIsAccepted() {
        ClientIpTrustPostureValidator validator = new ClientIpTrustPostureValidator(
                "none", new TrustedProxyProperties(List.of("10.0.0.0/8"), 1));

        validator.validate();
    }
}
