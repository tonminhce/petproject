package com.shop.gateway.filter;

import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ClientIpResolver {

    public static final String UNKNOWN_IP = "unknown";

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final TrustedProxyPolicy trustedProxyPolicy;

    public ClientIpResolver(final TrustedProxyPolicy trustedProxyPolicy) {
        this.trustedProxyPolicy = trustedProxyPolicy;
    }

    public String resolve(final ServerWebExchange exchange) {
        final String peerAddress = peerAddress(exchange);
        if (peerAddress == null) {
            return UNKNOWN_IP;
        }
        if (!trustedProxyPolicy.trustsForwardedHeaders() || !trustedProxyPolicy.isTrusted(peerAddress)) {
            return peerAddress;
        }
        return forwardedClientAddress(exchange).orElse(peerAddress);
    }

    private String peerAddress(final ServerWebExchange exchange) {
        final InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return null;
        }
        final InetAddress address = remoteAddress.getAddress();
        if (address != null) {
            return LiteralIpAddress.canonicalize(address.getHostAddress()).orElse(null);
        }
        return LiteralIpAddress.canonicalize(remoteAddress.getHostString()).orElse(null);
    }

    private Optional<String> forwardedClientAddress(final ServerWebExchange exchange) {
        final List<String> headerValues = exchange.getRequest().getHeaders().get(FORWARDED_FOR_HEADER);
        if (headerValues == null || headerValues.size() != 1) {
            return Optional.empty();
        }
        final List<String> entries = new ArrayList<>();
        for (final String rawEntry : headerValues.get(0).split(",")) {
            final String entry = rawEntry.trim();
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        final int index = Math.max(0, entries.size() - trustedProxyPolicy.hops());
        return LiteralIpAddress.canonicalize(entries.get(index));
    }
}
