package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.client.TaxServiceClient;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.PromotionReserveRequest;
import com.shop.orderservice.dto.internal.TaxCalculateRequest;
import com.shop.orderservice.dto.internal.TaxCalculateResponse;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.service.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * H14 — product snapshots used to be fetched serially in a {@code for} loop,
 * one HTTP round-trip per cart item. With N items the wall time was
 * {@code N * RTT} — a 50-item cart blocked the order thread for 50 round
 * trips. The fix fans the snapshot fetches out via {@link CompletableFuture}
 * on a small executor: the slowest single fetch sets the budget.
 *
 * <p>Traceparent propagation is fleet-wide via
 * {@code com.shop.common.web.TraceparentInterceptor} on the
 * {@code RestClient.Builder} — the call sites don't need to do anything.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingServiceImpl implements PricingService {

    private final ProductServiceClient productClient;
    private final TaxServiceClient taxClient;
    private final PromotionServiceClient promotionClient;

    /**
     * Bounded executor for fan-out. Pool size = 2 * available processors —
     * matches the Spring {@code @Async} default for I/O-bound work. Daemon
     * threads so a JVM shutdown doesn't wait on them.
     */
    private static final ExecutorService SNAPSHOT_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "pricing-snapshot-fanout");
                t.setDaemon(true);
                return t;
            });

    @Override
    public PricingBreakdown calculate(UUID orderId, UUID userId, List<CartItem> items, String couponCode) {
        // P1-5 — Reject couponCode upfront if promotion service is disabled.
        // Spec §5.2: "Có couponCode mà promotion disabled → 400 ORDER_PROMOTION_INVALID
        // (không âm thầm bỏ qua discount user nhập)". Silent ZERO discount would be
        // a UI lie — user sees a coupon field that doesn't work.
        if (couponCode != null && !couponCode.isBlank() && !promotionClient.isEnabled()) {
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, couponCode);
        }

        // 1. H14 — fetch product snapshots in parallel via CompletableFuture.
        Map<UUID, CompletableFuture<ProductSnapshot>> futures = new HashMap<>();
        for (CartItem item : items) {
            futures.put(item.getProductId(),
                CompletableFuture.supplyAsync(() -> productClient.getProduct(item.getProductId()),
                    SNAPSHOT_EXECUTOR));
        }

        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : items) {
            ProductSnapshot snapshot;
            try {
                snapshot = futures.get(item.getProductId()).join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException("Failed to fetch product snapshot for " + item.getProductId(), cause);
            }
            snapshots.put(item.getProductId(), snapshot);
            BigDecimal lineTotal = snapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
        }

        // 2. Apply promotion if coupon provided
        UUID promotionReservationId = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            var promoResp = promotionClient.reserve(
                new PromotionReserveRequest(couponCode, subtotal, userId, orderId)
            );
            promotionReservationId = promoResp.reservationId();
            discountAmount = promoResp.discountAmount();
        }

        // 3. Calculate tax on (subtotal - discount)
        BigDecimal taxableAmount = subtotal.subtract(discountAmount);
        var taxResp = calculateTaxSafely(taxableAmount, promotionReservationId);
        BigDecimal taxAmount = taxResp.taxAmount();

        // 4. Compute total
        BigDecimal total = taxableAmount.add(taxAmount);

        return new PricingBreakdown(subtotal, taxAmount, discountAmount, total, snapshots, promotionReservationId);
    }

    /**
     * T7 — the promotion reservation (step 2) already exists REMOTELY when the tax
     * call runs; a tax failure must not leak it until the TTL sweep. Best-effort
     * plain {@code /release} (the saga's compensation convention — the commit-side
     * {@code /release-committed} belongs to the confirm coordinator), then rethrow
     * the original error unchanged.
     */
    private TaxCalculateResponse calculateTaxSafely(BigDecimal taxableAmount, UUID promotionReservationId) {
        try {
            return taxClient.calculate(
                new TaxCalculateRequest(null, null, null, taxableAmount)  // taxClassId from product? defer
            );
        } catch (RuntimeException ex) {
            if (promotionReservationId != null) {
                try {
                    promotionClient.release(promotionReservationId);
                } catch (Exception rex) {
                    log.error("Failed to release promotion reservation {} after tax failure — TTL sweep covers",
                        promotionReservationId, rex);
                }
            }
            throw ex;
        }
    }
}
