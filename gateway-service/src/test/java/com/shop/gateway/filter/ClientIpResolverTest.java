package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"), 1);

    private static ClientIpResolver resolver(final List<String> trustedProxyCidrs, final int hops) {
        return new ClientIpResolver(new TrustedProxyPolicy(trustedProxyCidrs, hops));
    }

    @Test
    void requestWithoutForwardingHeaderIsLimitedByItsPeerAddress() {
        assertThat(resolver.resolve(exchange("127.0.0.1", null))).isEqualTo("127.0.0.1");
    }

    @Test
    void forgedForwardedHeaderCannotSelectTheBucketOfAnUntrustedPeer() {
        assertThat(resolver.resolve(exchange("203.0.113.9", "10.42.0.7"))).isEqualTo("203.0.113.9");
    }

    @Test
    void trustedProxyPeerMayDeclareTheClientAddress() {
        assertThat(resolver.resolve(exchange("10.0.0.1", "203.0.113.7, 70.41.3.18, 10.0.0.1")))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void trustedProxyPeerSelectsTheEntryOfTheConfiguredHop() {
        var twoHopResolver = resolver(List.of("10.0.0.0/8"), 2);
        assertThat(twoHopResolver.resolve(exchange("10.0.0.1", "203.0.113.7, 70.41.3.18")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void nonLiteralForwardedEntryIsRejectedAndThePeerAddressIsUsed() {
        assertThat(resolver.resolve(exchange("10.0.0.1", "attacker.example"))).isEqualTo("10.0.0.1");
    }

    @Test
    void forwardedEntryWithAPortIsNotALiteralAddress() {
        assertThat(resolver.resolve(exchange("10.0.0.1", "203.0.113.7:41234"))).isEqualTo("10.0.0.1");
    }

    @Test
    void forwardedHeaderIsIgnoredWhenNoTrustedProxyIsConfigured() {
        var noProxyResolver = resolver(List.of(), 0);
        assertThat(noProxyResolver.resolve(exchange("127.0.0.1", "203.0.113.7"))).isEqualTo("127.0.0.1");
    }

    @Test
    void forwardedHeaderIsIgnoredWhenTheHopCountIsNotConfigured() {
        var noHopResolver = resolver(List.of("10.0.0.0/8"), 0);
        assertThat(noHopResolver.resolve(exchange("10.0.0.1", "203.0.113.7"))).isEqualTo("10.0.0.1");
    }

    @Test
    void forwardedHeaderIsIgnoredWhenTheTrustedProxyAllowlistIsEmpty() {
        var noCidrResolver = resolver(List.of(), 1);
        assertThat(noCidrResolver.resolve(exchange("10.0.0.1", "203.0.113.7"))).isEqualTo("10.0.0.1");
    }

    @Test
    void blankForwardedHeaderFallsBackToRemoteAddress() {
        assertThat(resolver.resolve(exchange("127.0.0.1", "  "))).isEqualTo("127.0.0.1");
    }

    @Test
    void multipleForwardedHeadersAreIgnored() {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products")
                .header("X-Forwarded-For", "203.0.113.7", "10.0.0.2")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 101))
                .build();

        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo("10.0.0.1");
    }

    @Test
    void missingRemoteAddressResolvesToTheUnknownMarker() {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products").build();

        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo(ClientIpResolver.UNKNOWN_IP);
    }

    private MockServerWebExchange exchange(final String peerAddress, final String forwardedFor) {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products")
                .remoteAddress(new InetSocketAddress(peerAddress, 101));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request.build());
    }
}
