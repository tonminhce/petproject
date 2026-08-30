package com.shop.paymentservice.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockProviderTest {

    private final MockProvider provider = new MockProvider();

    @Test
    void nameIsMock() {
        assertThat(provider.name()).isEqualTo("mock");
    }

    @Test
    void captureReturnsMockUuidEventIdAndAccepts() {
        PaymentProvider.ProviderResult result = provider.capture(
                UUID.randomUUID(), new BigDecimal("19.99"), "USD", "idem-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).startsWith("mock-");
        assertThat(UUID.fromString(result.providerEventId().substring("mock-".length()))).isNotNull();
    }

    @Test
    void refundReturnsMockUuidEventIdAndAccepts() {
        PaymentProvider.ProviderResult result = provider.refund(
                UUID.randomUUID(), new BigDecimal("5.00"), "idem-2");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).startsWith("mock-");
        assertThat(UUID.fromString(result.providerEventId().substring("mock-".length()))).isNotNull();
    }

    @Test
    void eventIdsAreUnique() {
        PaymentProvider.ProviderResult first = provider.capture(UUID.randomUUID(), BigDecimal.TEN, "USD", "a");
        PaymentProvider.ProviderResult second = provider.capture(UUID.randomUUID(), BigDecimal.TEN, "USD", "b");

        assertThat(first.providerEventId()).isNotEqualTo(second.providerEventId());
    }
}
