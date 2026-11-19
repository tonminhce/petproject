package com.shop.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Optional;

/**
 * Uses the authenticated subject when available and falls back to the client
 * IP for public endpoints such as login and sign-up.
 */
public final class RateLimitKeyResolver implements KeyResolver {

    private static final String USER_KEY_PREFIX = "user:";
    private static final String IP_KEY_PREFIX = "ip:";

    private final int trustedProxyHops;
    private final Optional<XForwardedRemoteAddressResolver> forwardedAddressResolver;

    public RateLimitKeyResolver(final int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("trustedProxyHops must be >= 0");
        }
        this.trustedProxyHops = trustedProxyHops;
        this.forwardedAddressResolver = trustedProxyHops == 0
                ? Optional.empty()
                : Optional.of(XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops));
    }

    @Override
    public Mono<String> resolve(final ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(this::isUsableAuthentication)
                .map(Principal::getName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> USER_KEY_PREFIX + name)
                .switchIfEmpty(Mono.defer(() -> resolveIpKey(exchange)));
    }

    private boolean isUsableAuthentication(final Principal principal) {
        if (principal instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return !(principal instanceof Authentication authentication) || authentication.isAuthenticated();
    }

    private Mono<String> resolveIpKey(final ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = forwardedAddressResolver
                .map(resolver -> resolver.resolve(exchange))
                .orElseGet(() -> exchange.getRequest().getRemoteAddress());

        return Mono.justOrEmpty(remoteAddress)
                .map(this::hostAddress)
                .filter(host -> !host.isBlank())
                .map(host -> IP_KEY_PREFIX + host);
    }

    private String hostAddress(final InetSocketAddress address) {
        return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
    }
}
