package com.shop.paymentservice.service;

import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PaymentService {

    Payment create(CreatePaymentRequest req);

    Payment capture(UUID id);

    Payment refund(UUID id);

    Page<PaymentResponse> findAllByOrderId(UUID orderId, Pageable pageable);

    PaymentResponse findById(UUID id);
}
