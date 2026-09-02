package com.shop.shippingservice.service;

import com.shop.shippingservice.entity.ShipmentEvent;

/**
 * Carrier webhook entry point + retry hook used by
 * {@code shipping-service/.../scheduler/WebhookRetryScheduler}.
 */
public interface WebhookEventService {

    void handle(String carrier, byte[] rawBody, String signature);

    /**
     * C3 — re-run processing for a {@link ShipmentEvent} that the scheduler pulled
     * off the FAILED_RETRYABLE queue. The implementation should re-parse the
     * persisted payload and apply the same state-machine logic as {@link #handle}.
     * Throws to signal the attempt failed and the scheduler should increment
     * retry_count / schedule the next retry.
     */
    void retry(ShipmentEvent event);
}
