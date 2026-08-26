package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GlobalRateLimitPropertiesTest {

    @Test
    void keepsConfiguredGlobalTokenBucketValues() {
        var properties = new GlobalRateLimitProperties(true, 2_000, 4_000, 1);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.replenishRate()).isEqualTo(2_000);
        assertThat(properties.burstCapacity()).isEqualTo(4_000);
        assertThat(properties.requestedTokens()).isOne();
    }

    @Test
    void rejectsBucketSmallerThanRequestCost() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GlobalRateLimitProperties(true, 2_000, 1, 2))
                .withMessageContaining("burstCapacity");
    }
}
