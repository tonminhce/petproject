package com.shop.paymentservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.provider.PaymentProvider.ProviderResult;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.service.PaymentWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
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
        Optional<Payment> existing = (req.userId() != null)
                ? repository.findByIdempotencyKeyAndUserId(req.idempotencyKey(), req.userId())
                : repository.findByIdempotencyKey(req.idempotencyKey());

        return existing.orElseGet(() -> writer.insert(Payment.builder()
                .orderId(req.orderId())
                .userId(req.userId())
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
        // C7 fix — the provider response carries an `accepted` flag (Stripe
        // returns false for invalid_request_error / card_declined). The
        // previous code discarded the result, leaving a PENDING row on a real
        // provider FAILURE with no signal to the caller. Surface the rejection
        // as a domain 502 so the saga can compensate.
        ProviderResult result =
                provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        return payment;
    }

    @Override
    @Transactional
    public Payment refund(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw BusinessException.of(ErrorCode.REFUND_INVALID_STATE, payment.getStatus());
        }
        ProviderResult result =
                provider.refund(payment.getId(), payment.getAmount(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        return payment;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentResponse> findAllByOrderId(UUID orderId, Pageable pageable) {
        Page<Payment> page = orderId == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findAllByOrderIdOrderByCreatedAtDesc(orderId, pageable);
        return page.map(PaymentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse findById(UUID id) {
        return PaymentResponse.from(requirePayment(id));
    }

    private Payment requirePayment(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, id));
    }
}
