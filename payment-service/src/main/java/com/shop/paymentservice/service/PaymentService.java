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

    /**
     * The captured payment of an order, if there is one.
     *
     * <p>The order-service confirm gate asks this question and must be right
     * about it: a paged lookup would miss a captured payment that sits beyond
     * the first page, and the gate fails closed - the order could never be
     * confirmed and would be auto-cancelled while the customer had paid.</p>
     */
    Optional<PaymentResponse> findCapturedByOrderId(UUID orderId);

    PaymentResponse findById(UUID id);
}
