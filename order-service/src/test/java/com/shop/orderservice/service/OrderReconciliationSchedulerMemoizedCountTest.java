package com.shop.orderservice.service;

import com.shop.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderReconciliationSchedulerMemoizedCountTest {

    @Mock OrderRepository orderRepository;

    @Test
    void hundredScrapesResultInAtMostOneDbCall() {
        when(orderRepository.countByStatusAndCreatedAtBefore(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(42L);

        MemoizedCount memoized = new MemoizedCount(10_000L, () ->
            orderRepository.countByStatusAndCreatedAtBefore(
                com.shop.orderservice.constant.OrderStatus.PENDING,
                java.time.Instant.now()));

        for (int i = 0; i < 100; i++) {
            assertThat(memoized.get()).isEqualTo(42L);
        }

        verify(orderRepository, times(1))
            .countByStatusAndCreatedAtBefore(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void memoizedCountRefreshesAfterTtlExpires() throws InterruptedException {
        when(orderRepository.countByStatusAndCreatedAtBefore(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(1L, 2L);

        MemoizedCount memoized = new MemoizedCount(50L, () ->
            orderRepository.countByStatusAndCreatedAtBefore(
                com.shop.orderservice.constant.OrderStatus.PENDING,
                java.time.Instant.now()));

        assertThat(memoized.get()).isEqualTo(1L);

        assertThat(memoized.get()).isEqualTo(1L);

        Thread.sleep(80L);
        assertThat(memoized.get()).isEqualTo(2L);
        verify(orderRepository, times(2))
            .countByStatusAndCreatedAtBefore(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    private static final class MemoizedCount {
        private final long ttlNanos;
        private final java.util.function.LongSupplier supplier;
        private volatile long lastValue;
        private volatile long lastUpdatedNanos;

        MemoizedCount(long ttlMillis, java.util.function.LongSupplier supplier) {
            this.ttlNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ttlMillis);
            this.supplier = supplier;
            this.lastUpdatedNanos = 0L;
        }

        long get() {
            long now = System.nanoTime();
            if (now - lastUpdatedNanos < ttlNanos) {
                return lastValue;
            }
            synchronized (this) {
                if (now - lastUpdatedNanos < ttlNanos) {
                    return lastValue;
                }
                lastValue = supplier.getAsLong();
                lastUpdatedNanos = now;
                return lastValue;
            }
        }
    }
}
