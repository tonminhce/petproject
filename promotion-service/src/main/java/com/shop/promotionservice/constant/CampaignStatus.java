package com.shop.promotionservice.constant;

/**
 * Campaign lifecycle states. Mirrors the {@code ck_campaign_status} CHECK
 * constraint ({@code ACTIVE | INACTIVE}) on the {@code campaign} table.
 */
public enum CampaignStatus {
    ACTIVE, INACTIVE
}
