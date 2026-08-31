package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShippingMetrics;
import com.shop.shippingservice.service.ShipmentWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ReconciliationScheduler {

    private static final Set<ShipmentStatus> IN_FLIGHT = Arrays.stream(ShipmentStatus.values())
            .filter(ShipmentStatus::inFlight)
            .collect(Collectors.toUnmodifiableSet());

    private final ShipmentRepository repository;
    private final ShipmentWriter writer;
    private final ShippingMetrics metrics;
    private final Clock clock;
    private final long autoDeliverDays;

    public ReconciliationScheduler(ShipmentRepository repository, ShipmentWriter writer,
                                   ShippingMetrics metrics, Clock clock,
                                   @Value("${shop.shipping.auto-deliver-days:7}") long autoDeliverDays) {
        this.repository = repository;
        this.writer = writer;
        this.metrics = metrics;
        this.clock = clock;
        this.autoDeliverDays = autoDeliverDays;
    }

    @Scheduled(cron = "${shop.shipping.reconcile-cron:0 0 * * * *}")
    public void reconcile() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(autoDeliverDays));
        List<Shipment> stale = repository.findByStatusInAndLastCarrierUpdateBefore(IN_FLIGHT, cutoff);
        metrics.setStaleInflightCount(stale.size());
        if (stale.isEmpty()) {
            return;
        }
        log.info("Auto-delivering {} stale shipment(s) past cutoff {}", stale.size(), cutoff);
        for (Shipment shipment : stale) {
            shipment.setPreviousStatus(shipment.getStatus());
            shipment.setStatus(ShipmentStatus.DELIVERED);
            shipment.setAutoDelivered(true);
            shipment.setDeliveredAt(clock.instant());
            metrics.recordDelivered(true);
            writer.saveDelivered(shipment, true);
        }
    }
}
