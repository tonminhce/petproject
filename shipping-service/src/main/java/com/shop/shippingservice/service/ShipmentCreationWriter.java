package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentCreationTask;
import com.shop.shippingservice.repository.ShipmentCreationTaskRepository;
import com.shop.shippingservice.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ShipmentCreationWriter {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentCreationTaskRepository taskRepository;

    @Transactional
    public ShipmentCreationTask claim(UUID orderId, Carrier carrier, Instant now) {
        ShipmentCreationTask task = ShipmentCreationTask.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .carrier(carrier)
                .status(ShipmentCreationStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .build();
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public ShipmentCreationTask findByOrderId(UUID orderId) {
        return taskRepository.findByOrderId(orderId).orElse(null);
    }

    @Transactional
    public ShipmentCreationTask reopen(UUID taskId, Carrier carrier, Instant now) {
        ShipmentCreationTask task = requireTask(taskId);
        task.reopen(carrier, now);
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public ShipmentCreationTask complete(UUID taskId, Shipment shipment) {
        Shipment saved = shipmentRepository.saveAndFlush(shipment);
        ShipmentCreationTask task = requireTask(taskId);
        task.complete(saved.getId(), saved.getTrackingNumber());
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public ShipmentCreationTask completeWithExisting(UUID taskId, UUID shipmentId, String trackingNumber) {
        ShipmentCreationTask task = requireTask(taskId);
        task.complete(shipmentId, trackingNumber);
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public ShipmentCreationTask reschedule(UUID taskId, int attemptCount, Instant nextAttemptAt, String lastError) {
        ShipmentCreationTask task = requireTask(taskId);
        task.reschedule(attemptCount, nextAttemptAt, lastError);
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public ShipmentCreationTask failPermanently(UUID taskId, int attemptCount, String lastError) {
        ShipmentCreationTask task = requireTask(taskId);
        task.failPermanently(attemptCount, lastError);
        return taskRepository.saveAndFlush(task);
    }

    @Transactional
    public List<ShipmentCreationTask> claimDue(ShipmentCreationStatus status, Instant now,
                                               Instant staleBefore, int batchSize) {
        List<ShipmentCreationTask> due = taskRepository.findClaimable(
                status, now, staleBefore, PageRequest.of(0, batchSize));
        for (ShipmentCreationTask task : due) {
            task.claim(now);
        }
        return taskRepository.saveAllAndFlush(due);
    }

    private ShipmentCreationTask requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Shipment creation task not found: " + taskId));
    }
}
