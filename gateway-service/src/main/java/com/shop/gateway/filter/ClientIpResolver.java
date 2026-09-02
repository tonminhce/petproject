package com.shop.gateway.filter;

import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Optional;

/** Resolves the client address using the configured trusted proxy chain. */
public final class ClientIpResolver {
    private static final String UNKNOWN_IP = "unknown";
    private final Optional<XForwardedRemoteAddressResolver> forwardedAddressResolver;

    public ClientIpResolver() { this(0); }

    public ClientIpResolver(final int trustedProxyHops) {
        if (trustedProxyHops < 0) throw new IllegalArgumentException("trustedProxyHops must be >= 0");
        this.forwardedAddressResolver = trustedProxyHops == 0 ? Optional.empty()
                : Optional.of(XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops));
    }

    public String resolve(final ServerWebExchange exchange) {
        final InetSocketAddress address = forwardedAddressResolver.map(r -> r.resolve(exchange))
                .orElseGet(() -> exchange.getRequest().getRemoteAddress());
        if (address == null) return UNKNOWN_IP;
        if (address.getAddress() != null) return address.getAddress().getHostAddress();
        final String host = address.getHostString();
        final int colon = host.lastIndexOf(':');
        return colon > 0 && host.indexOf(':') == colon ? host.substring(0, colon) : host;
    }
}
