package com.shop.gateway.filter;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * F1 (final review) — the 3 edge filters ({@link AdminIpAllowlistFilter},
 * {@link RateLimitFilter}, {@link AdminRoleGateFilter}) must never decide on
 * the RAW request path alone: Spring Cloud Gateway route predicates decode
 * percent-encoding, so {@code /api/v1/backoffice/%72atings} reaches the
 * ratings backoffice route while a raw-string prefix match sees an unrelated
 * path — one encoded character would evade allowlist, rate limit and role
 * gate at once.
 *
 * <p><strong>Chosen strategy (uniform across all 3 edge filters, documented):
 * reject-with-400.</strong> A request whose raw path changes under
 * percent-decoding (single pass — catches single- and double-encoding alike;
 * malformed escape sequences count as encoded too) is answered with the fleet
 * {@code ERR-0400} envelope plus a WARN log. Scope lookup happens on the
 * fully-decoded path (decode-to-fixpoint, bounded), and the rate-limit bucket
 * is consumed first, so an evasion attempt is metered and gated — never
 * silently normalized into a pass. Fleet paths are canonical ASCII segments;
 * legitimate callers never percent-encode a controlled prefix.</p>
 *
 * <p>N-R1 — same evasion class, second vector: matrix variables
 * ({@code /api/v1/backoffice;r=1/ratings}). Route predicates match segments
 * with the {@code ;params} suffix stripped, so a semicolon can move a request
 * past the raw-string prefix match while it still lands on the gated route.
 * Any path containing {@code ;} is rejected with the same 400 envelope;
 * legitimate fleet paths are canonical ASCII segments without matrix
 * parameters.</p>
 */
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
