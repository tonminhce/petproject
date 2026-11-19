package com.shop.paymentservice.service;

import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse create(CreatePaymentRequest req);

    PaymentResponse capture(UUID id);

    PaymentResponse refund(UUID id);

    Page<PaymentResponse> findAllByOrderId(UUID orderId, Pageable pageable);

    Optional<PaymentResponse> findCapturedByOrderId(UUID orderId);

    PaymentResponse findById(UUID id);
}
