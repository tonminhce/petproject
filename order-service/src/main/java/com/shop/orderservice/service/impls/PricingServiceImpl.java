package com.shop.orderservice.service.impls;

import com.shop.common.core.constants.PlatformCurrency;
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
import org.slf4j.MDC;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingServiceImpl implements PricingService {

    private final ProductServiceClient productClient;
    private final TaxServiceClient taxClient;
    private final PromotionServiceClient promotionClient;

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
        if (couponCode != null && !couponCode.isBlank() && !promotionClient.isEnabled()) {
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, couponCode);
        }

        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Map<UUID, CompletableFuture<ProductSnapshot>> futures = new HashMap<>();
        for (CartItem item : items) {
            futures.put(item.getProductId(),
                CompletableFuture.supplyAsync(() -> {
                    Map<String, String> prev = MDC.getCopyOfContextMap();
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    try {
                        return productClient.getProduct(item.getProductId());
                    } finally {
                        if (prev != null) {
                            MDC.setContextMap(prev);
                        } else {
                            MDC.clear();
                        }
                    }
                }, SNAPSHOT_EXECUTOR));
        }

        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        String currency = null;
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
            String snapshotCurrency = resolveCurrency(snapshot);
            if (currency == null) {
                currency = snapshotCurrency;
            } else if (!currency.equals(snapshotCurrency)) {
                throw BusinessException.of(ErrorCode.ORDER_PRICING_CURRENCY_MISMATCH, currency, snapshotCurrency);
            }
            BigDecimal lineTotal = snapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
        }
        if (currency == null) {
            currency = PlatformCurrency.DEFAULT;
        }

        UUID promotionReservationId = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            var promoResp = promotionClient.reserve(
                new PromotionReserveRequest(couponCode, subtotal, currency, userId, orderId)
            );
            promotionReservationId = promoResp.reservationId();
            discountAmount = promoResp.discountAmount();
        }

        BigDecimal taxableAmount = subtotal.subtract(discountAmount);
        var taxResp = calculateTaxSafely(taxableAmount, currency, promotionReservationId);
        BigDecimal taxAmount = taxResp.taxAmount();

        BigDecimal total = taxableAmount.add(taxAmount);

        return new PricingBreakdown(subtotal, taxAmount, discountAmount, total, currency, snapshots, promotionReservationId);
    }

    private String resolveCurrency(ProductSnapshot snapshot) {
        String currency = snapshot.currency();
        if (currency == null || currency.isBlank()) {
            return PlatformCurrency.DEFAULT;
        }
        return PlatformCurrency.normalize(currency);
    }

    private TaxCalculateResponse calculateTaxSafely(BigDecimal taxableAmount, String currency, UUID promotionReservationId) {
        try {
            return taxClient.calculate(
                new TaxCalculateRequest(null, null, null, taxableAmount, currency)
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
