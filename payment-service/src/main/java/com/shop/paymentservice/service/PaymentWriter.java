package com.shop.paymentservice.service;

import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentWriter {

    private final PaymentRepository repository;

    @Transactional
    public Payment insert(Payment payment) {
        return repository.saveAndFlush(payment);
    }

    @Transactional
    public Payment saveAndFlush(Payment payment) {
        return repository.saveAndFlush(payment);
    }
}
