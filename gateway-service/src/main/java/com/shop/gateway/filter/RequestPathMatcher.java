package com.shop.gateway.filter;

import java.util.List;

/**
 * Segment-aware prefix matching for the edge filters.
 *
 * <p>A bare {@code startsWith()} treats {@code /api/v1/ratings-admin} as a
 * child of {@code /api/v1/ratings}: every route that merely BEGINS with a
 * public prefix inherits that prefix's bypass, which is how an allowlist turns
 * into an allowlist bypass the day a sibling route is added. The match has to
 * stop at a segment boundary - the path equals the prefix, or continues with
 * {@code "/"}.</p>
 */
final class RequestPathMatcher {

    private RequestPathMatcher() {
    }

    /**
     * @param path   the request path
     * @param prefix the prefix to match, without a trailing slash
     * @return true when the path IS the prefix or a child of it
     */
    static boolean matchesPrefix(final String path, final String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * @param path     the request path
     * @param prefixes the prefixes to match, without a trailing slash
     * @return true when the path matches any of the prefixes on a segment boundary
     */
    static boolean matchesAnyPrefix(final String path, final List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> matchesPrefix(path, prefix));
    }
}
