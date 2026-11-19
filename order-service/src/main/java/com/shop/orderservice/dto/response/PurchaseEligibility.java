package com.shop.orderservice.dto.response;

public record PurchaseEligibility(boolean eligible, long deliveredItemCount) {

    public static PurchaseEligibility of(long deliveredItemCount) {
        return new PurchaseEligibility(deliveredItemCount > 0, deliveredItemCount);
    }
}
