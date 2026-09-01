package com.shop.gateway.filter;

import org.springframework.core.Ordered;

/**
 * Explicit execution order of the D1/D4/D5 edge filter chain (binding: N2):
 *
 * <pre>IP allowlist -> rate limit -> role gate -> route</pre>
 *
 * <p>The values are named constants rather than inline magic numbers so the
 * contract is greppable and test-enforceable.</p>
 */
public final class FilterOrder {

    /**
     * D5 — backoffice IP allowlist runs first (fail-closed security control).
     */
    public static final int ADMIN_IP_ALLOWLIST = Ordered.HIGHEST_PRECEDENCE;

    /**
     * D4 — bucket4j per-IP edge rate limit, right after the allowlist.
     */
    public static final int RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * D1 — ADMIN realm-role gate on backoffice prefixes, last edge check.
     */
    public static final int ADMIN_ROLE_GATE = Ordered.HIGHEST_PRECEDENCE + 20;

    private FilterOrder() {
    }
}
