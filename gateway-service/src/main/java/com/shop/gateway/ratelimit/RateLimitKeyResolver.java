package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.ClientIpResolver;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;

public final class RateLimitKeyResolver implements KeyResolver {

    private static final String USER_KEY_PREFIX = "user:";
    private static final String IP_KEY_PREFIX = "ip:";

    private final ClientIpResolver clientIpResolver;

    public RateLimitKeyResolver(final ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
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
        final String address = clientIpResolver.resolve(exchange);
        if (address.isBlank() || ClientIpResolver.UNKNOWN_IP.equals(address)) {
            return Mono.empty();
        }
        return Mono.just(IP_KEY_PREFIX + address);
    }
}
