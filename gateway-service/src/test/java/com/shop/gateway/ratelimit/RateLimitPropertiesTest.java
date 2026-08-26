package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RateLimitPropertiesTest {

    @Test
    void keepsConfiguredTokenBucketValues() {
        var properties = new RateLimitProperties(true, 100, 200, 1, 0);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.replenishRate()).isEqualTo(100);
        assertThat(properties.burstCapacity()).isEqualTo(200);
        assertThat(properties.requestedTokens()).isEqualTo(1);
        assertThat(properties.trustedProxyHops()).isZero();
    }

    @Test
    void rejectsBucketSmallerThanRequestCost() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 1, 2, 0))
                .withMessageContaining("burstCapacity");
    }

    @Test
    void rejectsNegativeTrustedProxyHops() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 200, 1, -1))
                .withMessageContaining("trustedProxyHops");
    }
}
