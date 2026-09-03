package com.shop.paymentservice.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VNPayProviderTest {

    private VNPayProvider provider;

    @BeforeEach
    void setUp() {
        provider = new VNPayProvider("TMN_CODE", "HASH_SECRET", "https://vnpay.vn/pay");
    }

    @Test
    void name_returnsVnpay() {
        assertThat(provider.name()).isEqualTo("VNPAY");
    }

    @Test
    void capture_generatesValidResult() {
        UUID paymentId = UUID.randomUUID();
        PaymentProvider.ProviderResult result = provider.capture(
            paymentId, BigDecimal.valueOf(50000), "VND", "idemp-key-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isNotBlank();
    }

    @Test
    void refund_generatesValidResult() {
        UUID paymentId = UUID.randomUUID();
        PaymentProvider.ProviderResult result = provider.refund(
            paymentId, BigDecimal.valueOf(50000), "idemp-key-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).startsWith("vnp_rf_");
    }

    @Test
    void hmacSHA512_calculatesConsistentHash() {
        String hash1 = VNPayProvider.hmacSHA512("secret", "message");
        String hash2 = VNPayProvider.hmacSHA512("secret", "message");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(128); // 512 bits = 64 bytes = 128 hex chars
    }
}
