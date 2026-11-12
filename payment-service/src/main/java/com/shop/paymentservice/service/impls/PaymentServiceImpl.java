package com.shop.paymentservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.provider.PaymentProvider.ProviderResult;
import com.shop.paymentservice.provider.PaymentProviderFactory;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.service.PaymentWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository repository;
    private final PaymentWriter writer;
    private final PaymentProvider defaultProvider;
    private final PaymentProviderFactory providerFactory;

    @Autowired
    public PaymentServiceImpl(PaymentRepository repository, PaymentWriter writer, PaymentProvider defaultProvider,
                              @Autowired(required = false) PaymentProviderFactory providerFactory) {
        this.repository = repository;
        this.writer = writer;
        this.defaultProvider = defaultProvider;
        this.providerFactory = providerFactory;
    }

    public PaymentServiceImpl(PaymentRepository repository, PaymentWriter writer, PaymentProvider defaultProvider) {
        this(repository, writer, defaultProvider, null);
    }

    private PaymentProvider resolveProvider(String requestedProvider) {
        if (providerFactory != null && requestedProvider != null && !requestedProvider.isBlank()) {
            return providerFactory.getProvider(requestedProvider);
        }
        return defaultProvider;
    }

    @Override
    @Transactional
    public PaymentResponse create(CreatePaymentRequest req) {
        Optional<Payment> existing = (req.userId() != null)
                ? repository.findByIdempotencyKeyAndUserId(req.idempotencyKey(), req.userId())
                : repository.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), req);
        }

        PaymentProvider provider = resolveProvider(req.provider());

        Payment payment = Payment.builder()
                .orderId(req.orderId())
                .userId(req.userId())
                .amount(req.amount())
                .currency(req.currency())
                .status(PaymentStatus.PENDING)
                .provider(provider.name())
                .idempotencyKey(req.idempotencyKey())
                .build();
        try {
            return PaymentResponse.from(writer.insertIsolated(payment));
        } catch (DataIntegrityViolationException ex) {
            // Lost the same-key race: the unique index is the final arbiter.
            // The raw constraint violation must never surface as a 500.
            Payment winner = repository.findByIdempotencyKey(req.idempotencyKey())
                    .orElseThrow(() -> ex);
            return replay(winner, req);
        }
    }

    /**
     * The replay of an idempotent create.
     *
     * <p>The key identifies the REQUEST, not the payment. Handing back the
     * payment of another request would tell the caller that it created a
     * payment for its own order while the money is bound to a different one:
     * the capture then targets the wrong payment, the confirm gate of the real
     * order fails closed, and a paid order is auto-cancelled. The stored
     * payload is therefore compared before the replay is handed out, and a
     * difference is a conflict (PAY-5003).</p>
     */
    private PaymentResponse replay(Payment payment, CreatePaymentRequest req) {
        if (!samePayload(payment, req)) {
            log.warn("Idempotency key {} reused with a different payload (stored order {} amount {}, requested order {} amount {}) - refusing the replay",
                    req.idempotencyKey(), payment.getOrderId(), payment.getAmount(), req.orderId(), req.amount());
            throw BusinessException.of(ErrorCode.PAYMENT_DUPLICATE_REQUEST, req.idempotencyKey());
        }
        return PaymentResponse.from(payment);
    }

    /** The payment row stores the immutable payload, so it IS the request fingerprint. */
    private boolean samePayload(Payment payment, CreatePaymentRequest req) {
        return Objects.equals(payment.getOrderId(), req.orderId())
                && Objects.equals(payment.getUserId(), req.userId())
                && Objects.equals(payment.getCurrency(), req.currency())
                && payment.getAmount() != null
                && req.amount() != null
                && payment.getAmount().compareTo(req.amount()) == 0;
    }

    @Override
    @Transactional
    public PaymentResponse capture(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus());
        }
        PaymentProvider provider = resolveProvider(payment.getProvider());
        ProviderResult result =
                provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        payment.setPreviousStatus(PaymentStatus.PENDING);
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.captured.v1");
        return PaymentResponse.from(saved);
    }

    @Override
    @Transactional
    public PaymentResponse refund(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw BusinessException.of(ErrorCode.REFUND_INVALID_STATE, payment.getStatus());
        }
        PaymentProvider provider = resolveProvider(payment.getProvider());
        ProviderResult result =
                provider.refund(payment.getId(), payment.getAmount(), payment.getIdempotencyKey());
        if (!result.accepted()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        }
        payment.setPreviousStatus(PaymentStatus.CAPTURED);
        payment.setStatus(PaymentStatus.REFUNDED);
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.refunded.v1");
        return PaymentResponse.from(saved);
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
    public Optional<PaymentResponse> findCapturedByOrderId(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return repository.findFirstByOrderIdAndStatusOrderByCreatedAtDesc(orderId, PaymentStatus.CAPTURED)
                .map(PaymentResponse::from);
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
