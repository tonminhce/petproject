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
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    private final ProductServiceClient productClient;
    private final TaxServiceClient taxClient;
    private final PromotionServiceClient promotionClient;

    @Override
    public PricingBreakdown calculate(UUID orderId, UUID userId, List<CartItem> items, String couponCode) {
        // P1-5 — Reject couponCode upfront if promotion service is disabled.
        // Spec §5.2: "Có couponCode mà promotion disabled → 400 ORDER_PROMOTION_INVALID
        // (không âm thầm bỏ qua discount user nhập)". Silent ZERO discount would be
        // a UI lie — user sees a coupon field that doesn't work.
        if (couponCode != null && !couponCode.isBlank() && !promotionClient.isEnabled()) {
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, couponCode);
        }

        // 1. Fetch product snapshots (cached 10 min — see RestClient config + @Cacheable on productClient)
        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : items) {
            ProductSnapshot snapshot = productClient.getProduct(item.getProductId());
            snapshots.put(item.getProductId(), snapshot);
            BigDecimal lineTotal = snapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
        }

        // 2. Apply promotion if coupon provided
        // Reserve (not apply): the reservation is frozen at reserve time (spec D3) —
        // orderId comes from the saga's persist-early insert so the coordinator can
        // correlate, and reservationId propagates back for commit/release/compensation.
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
        var taxResp = taxClient.calculate(
            new TaxCalculateRequest(null, null, null, taxableAmount)  // taxClassId from product? defer
        );
        BigDecimal taxAmount = taxResp.taxAmount();

        // 4. Compute total
        BigDecimal total = taxableAmount.add(taxAmount);

        return new PricingBreakdown(subtotal, taxAmount, discountAmount, total, snapshots, promotionReservationId);
    }
}
