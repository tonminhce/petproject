package com.shop.gateway.filter;

import org.springframework.core.Ordered;

/**
 * <pre>IP allowlist -> rate limit -> role gate -> route</pre>
 * <p>The values are named constants rather than inline magic numbers so the
 * contract is greppable and test-enforceable.</p>
 */
public final class FilterOrder {

    /**
     * Backoffice IP allowlist runs first (fail-closed security control).
     */
    public static final int ADMIN_IP_ALLOWLIST = Ordered.HIGHEST_PRECEDENCE;

    public static final int RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 10;

    public static final int ADMIN_ROLE_GATE = Ordered.HIGHEST_PRECEDENCE + 20;

    private FilterOrder() {
    }
}
