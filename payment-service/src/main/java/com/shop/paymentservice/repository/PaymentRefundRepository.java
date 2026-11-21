package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {

    Optional<PaymentRefund> findByPaymentIdAndRefundReference(UUID paymentId, String refundReference);

    List<PaymentRefund> findByPaymentId(UUID paymentId);
}
