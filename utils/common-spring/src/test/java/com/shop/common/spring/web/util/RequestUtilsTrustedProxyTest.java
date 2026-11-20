package com.shop.common.spring.web.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestUtilsTrustedProxyTest {

    @Test
    @DisplayName("an untrusted peer cannot dictate the client address through a forwarding header")
    void anUntrustedPeerCannotDictateTheClientAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Real-IP", "10.0.0.7");

        assertThat(RequestUtils.getClientIp(request)).isEqualTo("203.0.113.9");
        assertThat(RequestUtils.getClientIp(request, TrustedProxyPolicy.of(List.of("10.0.0.0/8"), 1)))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("a trusted peer may declare the client address")
    void aTrustedPeerMayDeclareTheClientAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.4");

        TrustedProxyPolicy policy = TrustedProxyPolicy.of(List.of("203.0.113.0/24"), 1);

        assertThat(RequestUtils.getClientIp(request, policy)).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("a trusted proxy chain is walked from the right")
    void aTrustedProxyChainIsWalkedFromTheRight() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.4, 203.0.113.20, 203.0.113.9");

        TrustedProxyPolicy policy = TrustedProxyPolicy.of(List.of("203.0.113.0/24"), 2);

        assertThat(RequestUtils.getClientIp(request, policy)).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("an empty trusted proxy configuration ignores every forwarding header")
    void anEmptyTrustedProxyConfigurationIgnoresEveryForwardingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.4");

        assertThat(RequestUtils.getClientIp(request, TrustedProxyPolicy.of(List.of(), 0)))
                .isEqualTo("203.0.113.9");
        assertThat(RequestUtils.getClientIp(request, TrustedProxyPolicy.of(List.of("203.0.113.0/24"), 0)))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("a spoofed loopback address never reaches the local address helper")
    void aSpoofedLoopbackAddressNeverReachesTheLocalAddressHelper() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        String resolved = RequestUtils.getClientIp(request);

        assertThat(RequestUtils.isLocalAddress(resolved)).isFalse();
    }
}
