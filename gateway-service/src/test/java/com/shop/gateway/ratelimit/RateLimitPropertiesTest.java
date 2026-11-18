package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RateLimitPropertiesTest {

    @Test
    void keepsConfiguredTokenBucketValues() {
        var properties = new RateLimitProperties(true, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.replenishRate()).isEqualTo(100);
        assertThat(properties.burstCapacity()).isEqualTo(200);
        assertThat(properties.requestedTokens()).isEqualTo(1);
        assertThat(properties.outagePolicy()).isEqualTo(RedisOutagePolicy.FAIL_CLOSED);
    }

    @Test
    void rejectsBucketSmallerThanRequestCost() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 1, 2, RedisOutagePolicy.FAIL_CLOSED))
                .withMessageContaining("burstCapacity");
    }

    @Test
    void missingOutagePolicyFallsBackToFailClosed() {
        var properties = new RateLimitProperties(true, 100, 200, 1, null);

        assertThat(properties.outagePolicy()).isEqualTo(RedisOutagePolicy.FAIL_CLOSED);
    }
}
