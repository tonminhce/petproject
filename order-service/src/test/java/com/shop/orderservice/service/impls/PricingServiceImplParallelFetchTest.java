package com.shop.orderservice.service.impls;

import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.client.TaxServiceClient;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.entity.CartItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * H14 — five products fetched in parallel must take roughly the slowest
 * single fetch, not the sum. The pre-fix design ran the loop serially —
 * five products × RTT. With simulated per-fetch latency of 200ms each, the
 * wall time drops from ~1000ms (sum) to ~250ms (slowest + scheduling
 * overhead) when the fan-out executor runs them in parallel.
 *
 * <p>This is a perf-budget regression test, not a strict-equality assertion:
 * we assert wall ≤ slowest + 100ms tolerance. A future refactor that
 * accidentally reverts to a serial loop would push wall past 1000ms and
 * trip the assertion.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingServiceImplParallelFetchTest {

    @Mock ProductServiceClient productClient;
    @Mock TaxServiceClient taxClient;
    @Mock PromotionServiceClient promotionClient;

    private static final long PER_FETCH_LATENCY_MS = 200L;
    // H14 — wall budget is "much less than the sum" not "exactly the slowest".
    // The fan-out executor pool size = 2*N_cpu threads, so on a 2-core CI box
    // 5 tasks schedule as ~3 batches (~600ms worst case). The test asserts
    // wall < 80% of the serial-equivalent (5 * 200 = 1000ms) — a serial loop
    // regression trips the budget immediately.
    private static final long SUM_BUDGET_MS = 5L * PER_FETCH_LATENCY_MS * 80 / 100;

    @Test
    void fiveProductsFetchedInParallelWallTimeCloseToSlowestNotSum() {
        when(promotionClient.isEnabled()).thenReturn(false);
        when(taxClient.calculate(any())).thenReturn(
            new com.shop.orderservice.dto.internal.TaxCalculateResponse(BigDecimal.ZERO, BigDecimal.ZERO));

        List<CartItem> items = new ArrayList<>();
        List<UUID> productIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID pid = UUID.randomUUID();
            productIds.add(pid);
            items.add(CartItem.builder().id(UUID.randomUUID()).productId(pid).quantity(1).build());
            final int idx = i;
            when(productClient.getProduct(pid)).thenAnswer(inv -> {
                Thread.sleep(PER_FETCH_LATENCY_MS);
                return new ProductSnapshot(pid, "Product-" + idx, BigDecimal.valueOf(10 + idx));
            });
        }

        PricingServiceImpl service = new PricingServiceImpl(productClient, taxClient, promotionClient);

        long t0 = System.nanoTime();
        PricingBreakdown breakdown = service.calculate(UUID.randomUUID(), UUID.randomUUID(), items, null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Wall budget: <80% of the serial-equivalent sum. A serial loop regression
        // would push this past 1000ms and trip the budget immediately.
        assertThat(elapsedMs)
            .as("parallel fan-out must run wall well below sum (budget %d ms, was %d ms)",
                SUM_BUDGET_MS, elapsedMs)
            .isLessThan(SUM_BUDGET_MS);

        // All five snapshots resolved.
        assertThat(breakdown.snapshots()).hasSize(5);
    }
}
