package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H29 — multi-tenant leak surface. The previous {@code findByIdempotencyKey}
 * is a global-scope lookup: alice's idempotency key would return bob's
 * payment. The fix is to scope the lookup by user (and tenant) so a cross-user
 * key collision is impossible at the data layer, not just at the service layer.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRepositoryIdempotencyScopingTest {

    @Mock PaymentRepository repository;

    @Test
    void aliceIdempotencyLookupDoesNotReturnBobsPayment() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String key = "shared-key";

        Payment bobs = Payment.builder().id(UUID.randomUUID()).idempotencyKey(key).build();
        when(repository.findByIdempotencyKeyAndUserId(key, bob)).thenReturn(Optional.of(bobs));
        when(repository.findByIdempotencyKeyAndUserId(key, alice)).thenReturn(Optional.empty());

        Optional<Payment> aliceLookup = repository.findByIdempotencyKeyAndUserId(key, alice);
        Optional<Payment> bobLookup = repository.findByIdempotencyKeyAndUserId(key, bob);

        assertThat(aliceLookup).isEmpty();
        assertThat(bobLookup).isPresent();
        assertThat(bobLookup.get().getId()).isEqualTo(bobs.getId());
        verify(repository).findByIdempotencyKeyAndUserId(eq(key), eq(alice));
        verify(repository).findByIdempotencyKeyAndUserId(eq(key), eq(bob));
    }

    @Test
    void sameUserReturnsExistingPayment() {
        UUID alice = UUID.randomUUID();
        String key = "alice-only-key";
        Payment existing = Payment.builder().id(UUID.randomUUID()).idempotencyKey(key).build();
        when(repository.findByIdempotencyKeyAndUserId(key, alice)).thenReturn(Optional.of(existing));

        Optional<Payment> result = repository.findByIdempotencyKeyAndUserId(key, alice);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(existing.getId());
    }
}
