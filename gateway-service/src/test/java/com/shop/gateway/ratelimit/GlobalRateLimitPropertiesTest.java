package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GlobalRateLimitPropertiesTest {

    @Test
    void keepsConfiguredGlobalTokenBucketValues() {
        var properties = new GlobalRateLimitProperties(true, 2_000, 4_000, 1, 8, 20, 3);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.replenishRate()).isEqualTo(2_000);
        assertThat(properties.burstCapacity()).isEqualTo(4_000);
        assertThat(properties.requestedTokens()).isOne();
        assertThat(properties.lanes()).isEqualTo(8);
        assertThat(properties.fairShareClients()).isEqualTo(20);
        assertThat(properties.replicas()).isEqualTo(3);
    }

    @Test
    void rejectsBucketSmallerThanRequestCost() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GlobalRateLimitProperties(true, 2_000, 1, 2, 8, 20, 1))
                .withMessageContaining("burstCapacity");
    }
}
