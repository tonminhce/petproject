package com.shop.paymentservice.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MoMoProviderTest {

    private MoMoProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MoMoProvider("PARTNER", "ACCESS", "SECRET");
    }

    @Test
    void name_returnsMomo() {
        assertThat(provider.name()).isEqualTo("MOMO");
    }

    @Test
    void capture_generatesValidResult() {
        UUID paymentId = UUID.randomUUID();
        PaymentProvider.ProviderResult result = provider.capture(
            paymentId, BigDecimal.valueOf(50000), "VND", "idemp-key-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isEqualTo("momo_" + paymentId);
    }

    @Test
    void refund_generatesValidResult() {
        UUID paymentId = UUID.randomUUID();
        PaymentProvider.ProviderResult result = provider.refund(
            paymentId, BigDecimal.valueOf(50000), "idemp-key-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).startsWith("momo_rf_");
    }

    @Test
    void hmacSHA256_calculatesConsistentHash() {
        String hash1 = MoMoProvider.hmacSHA256("secret", "message");
        String hash2 = MoMoProvider.hmacSHA256("secret", "message");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // 256 bits = 32 bytes = 64 hex chars
    }
}
