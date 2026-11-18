package com.shop.gateway.filter;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

final class RequestPathGuard {

    private static final int MAX_DECODE_PASSES = 5;

    private RequestPathGuard() {
    }

    static boolean isEncoded(final String rawPath) {
        try {
            return !UriUtils.decode(rawPath, StandardCharsets.UTF_8).equals(rawPath);
        } catch (final IllegalArgumentException malformedSequence) {
            return true;
        }
    }

    static boolean containsMatrixVariable(final String rawPath) {
        return rawPath.indexOf(';') >= 0;
    }

    static boolean isEvasive(final String rawPath) {
        return isEncoded(rawPath) || containsMatrixVariable(rawPath);
    }

    static String decoded(final String rawPath) {
        String current = rawPath;
        for (int pass = 0; pass < MAX_DECODE_PASSES; pass++) {
            final String next;
            try {
                next = UriUtils.decode(current, StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException malformedSequence) {
                return current;
            }
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }
}
