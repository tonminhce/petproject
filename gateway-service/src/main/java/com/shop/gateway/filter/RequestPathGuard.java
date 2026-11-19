package com.shop.gateway.filter;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

final class RequestPathGuard {

    /**
     * Bounded decode-to-fixpoint; a well-formed request stabilizes after one
     * pass, deeper chains (double/triple encoding) are evasion attempts.
     */
    private static final int MAX_DECODE_PASSES = 5;

    private RequestPathGuard() {
    }

    /**
     * {@code true} when the raw path differs from its single-pass
     * percent-decoded form, or cannot be decoded at all (malformed escape
     * sequence). Detects double encoding ({@code %2572}) as well, because the
     * first pass already changes the raw bytes.
     */
    static boolean isEncoded(final String rawPath) {
        try {
            return !UriUtils.decode(rawPath, StandardCharsets.UTF_8).equals(rawPath);
        } catch (final IllegalArgumentException malformedSequence) {
            return true;
        }
    }

    /**
     * {@code true} when the raw path carries matrix variables
     * ({@code ;} in any segment — N-R1). Unlike percent-encoding this breaks
     * raw-string prefix matching itself, so it must be rejected before any
     * scope/prefix decision is trusted.
     */
    static boolean containsMatrixVariable(final String rawPath) {
        return rawPath.indexOf(';') >= 0;
    }

    /**
     * {@code true} when the raw path is percent-encoded or carries matrix
     * variables — the two known evasion vectors this guard exists for.
     */
    static boolean isEvasive(final String rawPath) {
        return isEncoded(rawPath) || containsMatrixVariable(rawPath);
    }

    /**
     * Fully-decoded form of the raw path (decode-to-fixpoint, bounded); the
     * path decoded as far as possible when a malformed escape sequence is
     * met, so scope matching stays conservative and the
     * {@link #isEncoded(String)} guard still rejects the request.
     */
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
