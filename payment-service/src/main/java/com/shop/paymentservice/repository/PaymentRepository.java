package com.shop.paymentservice.repository;

import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000")})
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    Optional<Payment> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(UUID orderId, PaymentStatus status);

    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId AND p.status IN (:statuses) ORDER BY p.createdAt DESC")
    List<Payment> findRefundableByOrderId(@Param("orderId") UUID orderId,
            @Param("statuses") Collection<PaymentStatus> statuses);

    boolean existsByOrderId(UUID orderId);
}
