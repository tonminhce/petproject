package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitKeyResolverTest {

    @Test
    void authenticatedRequestUsesStableUserKey() {
        var resolver = new RateLimitKeyResolver(0);
        var exchange = exchangeWith(
                MockServerHttpRequest.get("/api/v1/products")
                        .remoteAddress(new InetSocketAddress("192.0.2.10", 8080))
                        .build(),
                Mono.<Principal>just(new TestingAuthenticationToken("user-42", "credentials", List.of())));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("user:user-42");
    }

    @Test
    void anonymousRequestUsesRemoteIpKey() {
        var resolver = new RateLimitKeyResolver(0);
        var exchange = exchangeWith(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("192.0.2.10", 8080))
                        .build(),
                Mono.<Principal>empty());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:192.0.2.10");
    }

    @Test
    void anonymousAuthenticationTokenUsesRemoteIpKey() {
        var resolver = new RateLimitKeyResolver(0);
        var exchange = exchangeWith(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("192.0.2.10", 8080))
                        .build(),
                Mono.<Principal>just(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:192.0.2.10");
    }

    @Test
    void trustedForwardedRequestUsesClientIp() {
        var resolver = new RateLimitKeyResolver(1);
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 8080))
                .header("X-Forwarded-For", "192.0.2.10")
                .build();
        var exchange = exchangeWith(request, Mono.<Principal>empty());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:192.0.2.10");
    }

    @Test
    void requestWithoutIdentityOrRemoteAddressHasNoKey() {
        var resolver = new RateLimitKeyResolver(0);
        var exchange = exchangeWith(
                MockServerHttpRequest.get("/api/v1/auth/login").build(), Mono.<Principal>empty());

        assertThat(resolver.resolve(exchange).blockOptional()).isEmpty();
    }

    private ServerWebExchange exchangeWith(ServerHttpRequest request, Mono<Principal> principal) {
        var exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getPrincipal()).thenReturn(principal);
        return exchange;
    }
}
