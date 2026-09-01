package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N2 binding: IP allowlist (HIGHEST_PRECEDENCE) -> rate limit (+10) -> role gate (+20).
 */
class FilterOrderTest {

    @Test
    void edgeChainOrderIsExplicitAndNamed() {
        assertThat(FilterOrder.ADMIN_IP_ALLOWLIST).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(FilterOrder.RATE_LIMIT).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(FilterOrder.ADMIN_ROLE_GATE).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Test
    void executionOrderIsIpThenRateThenRole() {
        assertThat(FilterOrder.ADMIN_IP_ALLOWLIST).isLessThan(FilterOrder.RATE_LIMIT);
        assertThat(FilterOrder.RATE_LIMIT).isLessThan(FilterOrder.ADMIN_ROLE_GATE);
    }
}
