package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.carrier.CarrierFactory;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.entity.ShipmentCreationTask;
import com.shop.shippingservice.service.ShipmentCreationService;
import com.shop.shippingservice.service.ShipmentCreationWriter;
import com.shop.shippingservice.service.ShippingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class ShipmentCreationRetryScheduler {

    private final ShipmentCreationWriter writer;
    private final ShipmentCreationService creationService;
    private final CarrierFactory carrierFactory;
    private final Clock clock;
    private final ShippingMetrics metrics;
    private final int batchSize;
    private final long claimLeaseMs;

    public ShipmentCreationRetryScheduler(ShipmentCreationWriter writer,
                                          ShipmentCreationService creationService,
                                          CarrierFactory carrierFactory,
                                          Clock clock,
                                          ShippingMetrics metrics,
                                          @Value("${shop.shipping.carrier.retry-batch-size:50}") int batchSize,
                                          @Value("${shop.shipping.carrier.retry-claim-lease-ms:60000}") long claimLeaseMs) {
        this.writer = writer;
        this.creationService = creationService;
        this.carrierFactory = carrierFactory;
        this.clock = clock;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.claimLeaseMs = claimLeaseMs;
    }

    @Scheduled(fixedDelayString = "${shop.shipping.carrier.retry-poll-ms:30000}",
            initialDelayString = "${shop.shipping.carrier.retry-poll-ms:30000}")
    public void retryPendingCreations() {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(Duration.ofMillis(claimLeaseMs));
        List<ShipmentCreationTask> claimed;
        try {
            claimed = writer.claimDue(ShipmentCreationStatus.PENDING, now, staleBefore, batchSize);
        } catch (RuntimeException e) {
            log.error("Claiming pending shipment creation tasks failed", e);
            return;
        }
        for (ShipmentCreationTask task : claimed) {
            try {
                CarrierAdapter adapter = resolveAdapter(task.getCarrier());
                if (adapter == null) {
                    writer.failPermanently(task.getId(), task.getAttemptCount() + 1,
                            "carrier " + task.getCarrier() + " is not configured");
                    metrics.recordShipmentCreationFailedPermanently(task.getCarrier().name());
                    continue;
                }
                creationService.attempt(task, adapter);
            } catch (RuntimeException e) {
                log.error("Shipment creation retry failed for task {}", task.getId(), e);
            }
        }
    }

    private CarrierAdapter resolveAdapter(Carrier carrier) {
        CarrierAdapter adapter = carrierFactory.getCarrier(carrier);
        if (adapter == null || adapter.carrier() != carrier) {
            return null;
        }
        return adapter;
    }
}
