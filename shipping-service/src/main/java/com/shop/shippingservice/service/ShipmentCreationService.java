package com.shop.shippingservice.service;

import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentCreationTask;
import com.shop.shippingservice.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentCreationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_SECONDS = 60L;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final ShipmentCreationWriter writer;
    private final ShipmentRepository shipmentRepository;
    private final Clock clock;
    private final ShippingMetrics metrics;

    public void createForOrder(UUID orderId, CarrierAdapter adapter) {
        ShipmentCreationTask task = claim(orderId, adapter);
        if (task == null) {
            return;
        }
        attempt(task, adapter);
    }

    private ShipmentCreationTask claim(UUID orderId, CarrierAdapter adapter) {
        try {
            return writer.claim(orderId, adapter.carrier(), clock.instant());
        } catch (DataIntegrityViolationException e) {
            ShipmentCreationTask existing = writer.findByOrderId(orderId);
            if (existing == null) {
                log.info("Shipment creation for order {} is already claimed, the carrier is not called again", orderId);
                return null;
            }
            if (existing.getStatus() != ShipmentCreationStatus.COMPLETED
                    || shipmentRepository.existsByOrderId(orderId)) {
                log.info("Shipment creation for order {} is still claimed by an open task, "
                        + "the carrier is not called again", orderId);
                return null;
            }
            ShipmentCreationTask reopened = writer.reopen(existing.getId(), adapter.carrier(), clock.instant());
            log.info("Shipment creation for order {} is reopened because the previous shipment was removed", orderId);
            return reopened;
        }
    }

    public void attempt(ShipmentCreationTask task, CarrierAdapter adapter) {
        try {
            Optional<Shipment> existing = shipmentRepository.findByOrderId(task.getOrderId());
            if (existing.isPresent()) {
                Shipment shipment = existing.get();
                writer.completeWithExisting(task.getId(), shipment.getId(), shipment.getTrackingNumber());
                return;
            }
            CarrierAdapter.ShipmentDraft draft = adapter.createShipment(task.getOrderId());
            Shipment shipment = Shipment.builder()
                    .id(UUID.randomUUID())
                    .orderId(task.getOrderId())
                    .carrier(task.getCarrier())
                    .trackingNumber(draft.trackingNumber())
                    .status(ShipmentStatus.CREATED)
                    .carrierAcknowledgedAt(clock.instant())
                    .build();
            shipment.applyCarrierStatus(draft.initialStatus());
            shipment.setAutoDelivered(draft.initialStatus() == ShipmentStatus.DELIVERED);
            writer.complete(task.getId(), shipment);
            metrics.recordShipmentCreated(task.getCarrier().name());
        } catch (RuntimeException e) {
            recordFailure(task, e);
        }
    }

    private void recordFailure(ShipmentCreationTask task, RuntimeException failure) {
        int attempt = task.getAttemptCount() + 1;
        String message = truncate(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        try {
            if (attempt >= MAX_ATTEMPTS) {
                writer.failPermanently(task.getId(), attempt, message);
                metrics.recordShipmentCreationFailedPermanently(task.getCarrier().name());
                log.error("Shipment creation for order {} failed permanently after {} attempt(s)",
                        task.getOrderId(), attempt, failure);
                return;
            }
            Instant nextAttemptAt = clock.instant().plusSeconds(backoffSeconds(attempt));
            writer.reschedule(task.getId(), attempt, nextAttemptAt, message);
            metrics.recordShipmentCreationFailed(task.getCarrier().name());
            log.warn("Shipment creation for order {} attempt {}/{} failed, next attempt at {}",
                    task.getOrderId(), attempt, MAX_ATTEMPTS, nextAttemptAt, failure);
        } catch (RuntimeException bookkeepingFailure) {
            log.error("Shipment creation bookkeeping failed for order {}", task.getOrderId(), bookkeepingFailure);
        }
    }

    static long backoffSeconds(int attempt) {
        long multiplier = 1;
        for (int i = 1; i < attempt; i++) {
            multiplier *= 5L;
        }
        return BASE_BACKOFF_SECONDS * multiplier;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
