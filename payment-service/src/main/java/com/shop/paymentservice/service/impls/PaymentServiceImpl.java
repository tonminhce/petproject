package com.shop.paymentservice.service.impls;

import com.shop.common.core.constants.PlatformCurrency;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentRefund;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.provider.PaymentProvider.ProviderResult;
import com.shop.paymentservice.provider.PaymentProviderFactory;
import com.shop.paymentservice.repository.PaymentRefundRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.service.PaymentStateMachine;
import com.shop.paymentservice.service.PaymentWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private static final List<PaymentStatus> REFUNDABLE_STATUSES =
            List.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);

    private final PaymentRepository repository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentWriter writer;
    private final PaymentProvider defaultProvider;
    private final PaymentProviderFactory providerFactory;

    @Autowired
    public PaymentServiceImpl(PaymentRepository repository, PaymentRefundRepository refundRepository,
                              PaymentWriter writer, PaymentProvider defaultProvider,
                              @Autowired(required = false) PaymentProviderFactory providerFactory) {
        this.repository = repository;
        this.refundRepository = refundRepository;
        this.writer = writer;
        this.defaultProvider = defaultProvider;
        this.providerFactory = providerFactory;
    }

    public PaymentServiceImpl(PaymentRepository repository, PaymentRefundRepository refundRepository,
                              PaymentWriter writer, PaymentProvider defaultProvider) {
        this(repository, refundRepository, writer, defaultProvider, null);
    }

    private PaymentProvider resolveProviderForNewPayment(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            return defaultProvider;
        }
        return resolveRegisteredProvider(requestedProvider);
    }

    private PaymentProvider resolveRegisteredProvider(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                    "A payment provider name is required");
        }
        if (providerFactory != null) {
            return providerFactory.getProvider(requestedProvider);
        }
        String wiredProviderName = defaultProvider.name();
        if (wiredProviderName == null || !wiredProviderName.equalsIgnoreCase(requestedProvider.trim())) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                    "Payment provider is not registered: " + requestedProvider);
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

        PaymentProvider provider = resolveProviderForNewPayment(req.provider());
        String currency = resolveCurrency(req.currency());

        Payment payment = Payment.builder()
                .orderId(req.orderId())
                .userId(req.userId())
                .amount(req.amount())
                .currency(currency)
                .refundedCurrency(currency)
                .status(PaymentStatus.PENDING)
                .provider(provider.name())
                .idempotencyKey(req.idempotencyKey())
                .build();
        try {
            return PaymentResponse.from(writer.insertIsolated(payment));
        } catch (DataIntegrityViolationException ex) {
            Payment winner = repository.findByIdempotencyKey(req.idempotencyKey())
                    .orElseThrow(() -> ex);
            return replay(winner, req);
        }
    }

    private PaymentResponse replay(Payment payment, CreatePaymentRequest req) {
        if (!samePayload(payment, req)) {
            log.warn("Idempotency key {} reused with a different payload (stored order {} amount {}, requested order {} amount {}) - refusing the replay",
                    req.idempotencyKey(), payment.getOrderId(), payment.getAmount(), req.orderId(), req.amount());
            throw BusinessException.of(ErrorCode.PAYMENT_DUPLICATE_REQUEST, req.idempotencyKey());
        }
        return PaymentResponse.from(payment);
    }

    private boolean samePayload(Payment payment, CreatePaymentRequest req) {
        return Objects.equals(payment.getOrderId(), req.orderId())
                && Objects.equals(payment.getUserId(), req.userId())
                && Objects.equals(payment.getCurrency(), resolveCurrency(req.currency()))
                && payment.getAmount() != null
                && req.amount() != null
                && payment.getAmount().compareTo(req.amount()) == 0;
    }

    private String resolveCurrency(String requestedCurrency) {
        if (requestedCurrency == null || requestedCurrency.isBlank()) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_UNSUPPORTED, requestedCurrency);
        }
        String currency = PlatformCurrency.normalize(requestedCurrency.trim());
        if (!PlatformCurrency.isSupported(currency)) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_UNSUPPORTED, requestedCurrency);
        }
        return currency;
    }

    @Override
    @Transactional
    public PaymentResponse capture(UUID id) {
        Payment payment = requirePayment(id);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus());
        }
        PaymentProvider provider = resolveRegisteredProvider(payment.getProvider());
        ProviderResult result =
                provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
        return switch (result.outcome()) {
            case CONFIRMED -> captureConfirmed(payment, result);
            case REQUIRES_CONFIRMATION -> awaitClientConfirmation(payment, result);
            case INDETERMINATE -> throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, result.providerEventId());
            case REJECTED -> throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        };
    }

    private PaymentResponse captureConfirmed(Payment payment, ProviderResult result) {
        payment.setPreviousStatus(PaymentStatus.PENDING);
        payment.setStatus(PaymentStatus.CAPTURED);
        if (result.providerEventId() != null && !result.providerEventId().isBlank()) {
            payment.setProviderIntentId(result.providerEventId());
        }
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.captured.v1");
        return PaymentResponse.from(saved);
    }

    private PaymentResponse awaitClientConfirmation(Payment payment, ProviderResult result) {
        if (result.providerEventId() != null && !result.providerEventId().isBlank()) {
            payment.setProviderIntentId(result.providerEventId());
        }
        payment.setClientSecret(result.clientSecret());
        return PaymentResponse.from(writer.save(payment));
    }

    @Override
    @Transactional
    public PaymentResponse refund(UUID id, BigDecimal requestedAmount, String requestedCurrency,
            UUID refundReference) {
        Payment payment = requirePaymentForUpdate(id);
        return refund(payment, requestedAmount, requestedCurrency, refundReference);
    }

    private PaymentResponse refund(Payment payment, BigDecimal requestedAmount, String requestedCurrency,
            UUID refundReference) {
        if (refundReference == null) {
            throw BusinessException.of(ErrorCode.BAD_REQUEST, refundReference);
        }
        String reference = refundReference.toString();
        if (refundRepository.findByPaymentIdAndRefundReference(payment.getId(), reference).isPresent()) {
            return PaymentResponse.from(payment);
        }
        PaymentStatus currentStatus = payment.getStatus();
        if (currentStatus != PaymentStatus.CAPTURED && currentStatus != PaymentStatus.PARTIALLY_REFUNDED) {
            throw BusinessException.of(ErrorCode.REFUND_INVALID_STATE, currentStatus);
        }
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, requestedAmount);
        }
        String currency = resolveRefundCurrency(requestedCurrency, payment);
        BigDecimal alreadyRefunded = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO
                : payment.getRefundedAmount();
        BigDecimal refundable = payment.getAmount().subtract(alreadyRefunded);
        if (requestedAmount.compareTo(refundable) > 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, refundable);
        }
        PaymentProvider provider = resolveRegisteredProvider(payment.getProvider());
        ProviderResult result = provider.refund(payment.getId(), requestedAmount, currency,
                refundProviderKey(reference), payment.getProviderIntentId());
        return switch (result.outcome()) {
            case CONFIRMED -> refundConfirmed(payment, requestedAmount, currency, reference,
                    result.providerEventId());
            case INDETERMINATE -> throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, result.providerEventId());
            case REQUIRES_CONFIRMATION, REJECTED ->
                    throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED, result.providerEventId());
        };
    }

    private String resolveRefundCurrency(String requestedCurrency, Payment payment) {
        String currency = PlatformCurrency.normalize(requestedCurrency);
        if (!PlatformCurrency.isSupported(currency)) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_UNSUPPORTED, requestedCurrency);
        }
        if (!currency.equals(PlatformCurrency.normalize(payment.getCurrency()))) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_MISMATCH, payment.getCurrency(), currency);
        }
        return currency;
    }

    private String refundProviderKey(String refundReference) {
        return "refund-" + refundReference;
    }

    private PaymentResponse refundConfirmed(Payment payment, BigDecimal requestedAmount, String currency,
            String refundReference, String providerEventId) {
        BigDecimal refunded = confirmedRefundedAmount(payment.getId()).add(requestedAmount);
        refundRepository.save(PaymentRefund.builder()
                .paymentId(payment.getId())
                .refundReference(refundReference)
                .amount(requestedAmount)
                .currency(currency)
                .provider(payment.getProvider())
                .providerEventId(providerEventId)
                .build());
        PaymentStatus previousStatus = payment.getStatus();
        PaymentStatus nextStatus = refunded.compareTo(payment.getAmount()) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        payment.setPreviousStatus(previousStatus);
        payment.setRefundedAmount(refunded);
        payment.setRefundedCurrency(currency);
        payment.setStatus(PaymentStateMachine.transition(previousStatus, nextStatus));
        Payment saved = writer.save(payment);
        writer.publishLifecycleEvent(saved, "payment.refunded.v1");
        return PaymentResponse.from(saved);
    }

    private BigDecimal confirmedRefundedAmount(UUID paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(PaymentRefund::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
    public Optional<PaymentResponse> findRefundableByOrderId(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return repository.findRefundableByOrderId(orderId, REFUNDABLE_STATUSES).stream()
                .findFirst()
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

    private Payment requirePaymentForUpdate(UUID id) {
        try {
            return repository.findByIdForUpdate(id)
                    .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, id));
        } catch (PessimisticLockingFailureException | QueryTimeoutException ex) {
            log.warn("The payment row {} could not be locked for the refund - the refund is refused", id);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, id);
        }
    }
}
