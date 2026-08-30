package com.shop.paymentservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.service.PaymentWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository repository;
    private final PaymentWriter writer;
    private final PaymentProvider provider;

    @Override
    @Transactional
    public Payment create(CreatePaymentRequest req) {
        return repository.findByIdempotencyKey(req.idempotencyKey())
                .orElseGet(() -> writer.insert(Payment.builder()
                        .orderId(req.orderId())
                        .amount(req.amount())
                        .currency(req.currency())
                        .status(PaymentStatus.PENDING)
                        .provider(provider.name())
                        .idempotencyKey(req.idempotencyKey())
                        .build()));
    }

    @Override
    @Transactional
    public Payment capture(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus());
        }
        provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        return payment;
    }

    @Override
    @Transactional
    public Payment refund(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw BusinessException.of(ErrorCode.REFUND_INVALID_STATE, payment.getStatus());
        }
        provider.refund(payment.getId(), payment.getAmount(), payment.getIdempotencyKey());
        return payment;
    }

    private Payment requirePayment(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, id));
    }
}
