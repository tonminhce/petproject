package com.shop.gateway.filter;

import org.springframework.web.server.ServerWebExchange;

/**
 * Resolves the client IP for the edge filters (D4 rate-limit key, D5 allowlist).
 *
 * <p><strong>Assumption (documented):</strong> the gateway sits behind at most
 * one trusted edge that appends the client address to {@code X-Forwarded-For}.
 * We trust the <strong>first</strong> XFF entry only — the originating client —
 * and ignore the rest (they are hop addresses an attacker could forge).
 * When the header is absent we fall back to the TCP remote address.
 * A hardened deployment should keep this behaviour only with an edge that
 * overwrites (not appends) untrusted XFF input.</p>
 */
public final class ClientIpResolver {

    private static final String UNKNOWN_IP = "unknown";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    public String resolve(final ServerWebExchange exchange) {
        final String forwarded = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
        final String firstEntry = firstForwardedEntry(forwarded);
        if (firstEntry != null) {
            return stripPort(firstEntry);
        }
        return remoteAddress(exchange);
    }

    private String firstForwardedEntry(final String forwarded) {
        if (forwarded == null || forwarded.isBlank()) {
            return null;
        }
        final String first = forwarded.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    /**
     * Forwarded values may carry a port ({@code 203.0.113.7:41234}); the
     * allowlist matches on the address only.
     */
    private String stripPort(final String hostAndMaybePort) {
        final int colon = hostAndMaybePort.lastIndexOf(':');
        if (colon > 0 && hostAndMaybePort.indexOf(':') == colon && !hostAndMaybePort.contains("]")) {
            return hostAndMaybePort.substring(0, colon);
        }
        return hostAndMaybePort;
    }

    private String remoteAddress(final ServerWebExchange exchange) {
        final var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return UNKNOWN_IP;
        }
        return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }
}
