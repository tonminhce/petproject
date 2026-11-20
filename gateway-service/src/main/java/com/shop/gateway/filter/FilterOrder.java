package com.shop.gateway.filter;

import org.springframework.core.Ordered;

public final class FilterOrder {

    public static final int ADMIN_IP_ALLOWLIST = Ordered.HIGHEST_PRECEDENCE;

    public static final int RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 10;

    public static final int ADMIN_ROLE_GATE = Ordered.HIGHEST_PRECEDENCE + 20;

    public static final int DOWNSTREAM_RESILIENCE = Ordered.HIGHEST_PRECEDENCE + 30;

    private FilterOrder() {
    }
}
