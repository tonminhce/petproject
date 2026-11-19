package com.shop.gateway.filter;

import java.util.List;

final class RequestPathMatcher {

    private RequestPathMatcher() {
    }

    static boolean matchesPrefix(final String path, final String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    static boolean matchesAnyPrefix(final String path, final List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> matchesPrefix(path, prefix));
    }
}
