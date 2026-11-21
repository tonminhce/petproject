package com.shop.paymentservice.service;

import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse create(CreatePaymentRequest req);

    PaymentResponse capture(UUID id);

    PaymentResponse refund(UUID id, BigDecimal amount, String currency, UUID refundReference);

    Page<PaymentResponse> findAllByOrderId(UUID orderId, Pageable pageable);

    Optional<PaymentResponse> findCapturedByOrderId(UUID orderId);

    Optional<PaymentResponse> findRefundableByOrderId(UUID orderId);

    PaymentResponse findById(UUID id);
}
