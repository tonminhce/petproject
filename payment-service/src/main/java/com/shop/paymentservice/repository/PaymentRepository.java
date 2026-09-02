package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * H29 — multi-tenant-scoped idempotency-key lookup. Two callers passing
     * the same idempotency key never collide across users/tenants; an empty
     * result is the "no payment for this user with this key" signal that the
     * service layer uses to decide between INSERT and REPLAY.
     */
    Optional<Payment> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    boolean existsByOrderId(UUID orderId);
}
