package com.shop.promotionservice.constant;

/**
 * Coupon usage reservation lifecycle. Mirrors the {@code ck_cur_status}
 * CHECK constraint ({@code PENDING | COMMITTED | RELEASED | EXPIRED}) on the
 * {@code coupon_usage_reservation} table.
 */
public enum UsageStatus {
    PENDING, COMMITTED, RELEASED, EXPIRED
}
