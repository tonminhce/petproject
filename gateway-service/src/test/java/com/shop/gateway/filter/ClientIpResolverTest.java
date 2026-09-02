package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.net.InetSocketAddress;
import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    private final ClientIpResolver resolver = new ClientIpResolver(1);

    @Test
    void selectsClientAddressFromConfiguredTrustedChain() {
        var exchange = exchangeWithXff("203.0.113.7, 70.41.3.18, 10.0.0.1");
        assertThat(resolver.resolve(exchange)).isEqualTo("10.0.0.1");
    }

    @Test
    void doesNotTrustForwardedHeaderWhenNoProxyIsConfigured() {
        var noProxyResolver = new ClientIpResolver(0);
        assertThat(noProxyResolver.resolve(exchangeWithXff("203.0.113.7"))).isEqualTo("127.0.0.1");
    }

    @Test
    void fallsBackToRemoteAddressWithoutForwardedHeader() {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 101)).build();
        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo("127.0.0.1");
    }

    @Test
    void blankForwardedHeaderFallsBackToRemoteAddress() {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products")
                .header("X-Forwarded-For", "  ")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 101)).build();
        assertThat(resolver.resolve(MockServerWebExchange.from(request))).isEqualTo("127.0.0.1");
    }

    @Test
    void stripsPortFromForwardedEntry() {
        assertThat(resolver.resolve(exchangeWithXff("203.0.113.7:41234"))).isEqualTo("203.0.113.7");
    }

    private MockServerWebExchange exchangeWithXff(String forwardedFor) {
        var request = MockServerHttpRequest.get("http://gateway.local/api/v1/products")
                .header("X-Forwarded-For", forwardedFor)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 101)).build();
        return MockServerWebExchange.from(request);
    }
}
