package com.shop.paymentservice.service;

import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.entity.Payment;

import java.util.UUID;

public interface PaymentService {

    Payment create(CreatePaymentRequest req);

    Payment capture(UUID id);

    Payment refund(UUID id);
}
