package com.shop.paymentservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentRefund;
import com.shop.paymentservice.provider.MockProvider;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.provider.PaymentProviderFactory;
import com.shop.paymentservice.repository.PaymentRefundRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentWriter;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REFUND_REFERENCE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_REFUND_REFERENCE = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String IDEMPOTENCY_KEY = "ord-1-pay-1";
    private static final String REFUND_KEY = "refund-" + REFUND_REFERENCE;
    private static final BigDecimal AMOUNT = new BigDecimal("98.00");
    private static final String CURRENCY = "USD";

    @Mock PaymentRepository repository;
    @Mock PaymentRefundRepository refundRepository;
    @Mock PaymentWriter writer;
    @Mock PaymentProvider provider;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(repository, refundRepository, writer, provider);
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(ORDER_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY);
    }

    private Payment payment(PaymentStatus status) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .currency(CURRENCY)
                .status(status)
                .provider("mock")
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();
    }

    private PaymentRefund confirmedRefund(UUID reference, BigDecimal amount) {
        return PaymentRefund.builder()
                .paymentId(PAYMENT_ID)
                .refundReference(reference.toString())
                .amount(amount)
                .currency(CURRENCY)
                .provider("mock")
                .providerEventId("mock-evt-" + reference)
                .build();
    }

    @Test
    void create_happyPath_insertsPendingPayment() {
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(provider.name()).thenReturn("mock");
        Payment saved = payment(PaymentStatus.PENDING);
        when(writer.insertIsolated(any(Payment.class))).thenReturn(saved);

        PaymentResponse result = service.create(request());

        assertThat(result.id()).isEqualTo(saved.getId());
        assertThat(result.status()).isEqualTo(saved.getStatus());
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).insertIsolated(captor.capture());
        Payment inserted = captor.getValue();
        assertThat(inserted.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(inserted.getCurrency()).isEqualTo(CURRENCY);
        assertThat(inserted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(inserted.getProvider()).isEqualTo("mock");
        assertThat(inserted.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    @Test
    void create_existingIdempotencyKey_returnsExistingRowWithoutInsert() {
        Payment existing = payment(PaymentStatus.PENDING);
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        PaymentResponse result = service.create(request());

        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo(existing.getStatus());
        verifyNoInteractions(writer);
    }

    @Test
    void create_withUnsupportedCurrency_isRejectedAndNothingIsPersisted() {
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

        CreatePaymentRequest request = new CreatePaymentRequest(
                ORDER_ID, AMOUNT, "EUR", IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CURRENCY_UNSUPPORTED.getCode()));

        verifyNoInteractions(writer);
    }

    @Test
    void create_withLowercaseCurrency_normalizesToTheUppercaseCode() {
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(provider.name()).thenReturn("mock");
        when(writer.insertIsolated(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreatePaymentRequest(ORDER_ID, AMOUNT, "usd", IDEMPOTENCY_KEY));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).insertIsolated(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().getRefundedCurrency()).isEqualTo("USD");
    }

    @Test
    void capture_nonPendingPayment_throwsPay5004() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));

        assertThatThrownBy(() -> service.capture(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_INVALID_STATE.getCode()));
        verifyNoInteractions(provider);
    }

    @Test
    void capture_pendingPayment_callsProviderWithPaymentFields() {
        Payment pending = payment(PaymentStatus.PENDING);
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(provider.name()).thenReturn("mock");
        when(provider.capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-1"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = service.capture(PAYMENT_ID);

        verify(provider).capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY);
        verify(writer).save(pending);
        verify(writer).publishLifecycleEvent(pending, "payment.captured.v1");
        assertThat(result.id()).isEqualTo(PAYMENT_ID);
        assertThat(result.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(pending.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.getProviderIntentId()).isEqualTo("mock-evt-1");
    }

    @Test
    void capture_requiresConfirmation_keepsPaymentPendingAndStoresIntentIdAndClientSecret() {
        Payment pending = payment(PaymentStatus.PENDING);
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(provider.name()).thenReturn("mock");
        when(provider.capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY))
                .thenReturn(PaymentProvider.ProviderResult.requiresConfirmation("pi_1", "pi_secret_1"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = service.capture(PAYMENT_ID);

        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.providerIntentId()).isEqualTo("pi_1");
        assertThat(result.clientSecret()).isEqualTo("pi_secret_1");
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.getPreviousStatus()).isNull();
        assertThat(pending.getProviderIntentId()).isEqualTo("pi_1");
        assertThat(pending.getClientSecret()).isEqualTo("pi_secret_1");
        verify(writer).save(pending);
        verify(writer, never()).publishLifecycleEvent(any(Payment.class), any(String.class));
    }

    @Test
    void capture_indeterminate_throwsRetryableErrorAndLeavesThePaymentPending() {
        Payment pending = payment(PaymentStatus.PENDING);
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(provider.name()).thenReturn("mock");
        when(provider.capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY))
                .thenReturn(PaymentProvider.ProviderResult.indeterminate("stripe-timeout"));

        assertThatThrownBy(() -> service.capture(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException failure = (BusinessException) e;
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(writer);
    }

    @Test
    void capture_paymentOfAnUnregisteredProvider_isRejectedAndNeverFallsBackToTheDefaultProvider() {
        PaymentProviderFactory factory = new PaymentProviderFactory(List.of(new MockProvider()), "mock");
        PaymentServiceImpl serviceWithFactory =
                new PaymentServiceImpl(repository, refundRepository, writer, provider, factory);
        Payment pending = payment(PaymentStatus.PENDING);
        pending.setProvider("absent-psp");
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> serviceWithFactory.capture(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
        verifyNoInteractions(provider);
        verifyNoInteractions(writer);
    }

    @Test
    void capture_providerRejected_throwsPaymentProviderRejected() {
        Payment pending = payment(PaymentStatus.PENDING);
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(provider.name()).thenReturn("mock");
        when(provider.capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY))
                .thenReturn(PaymentProvider.ProviderResult.rejected("card_declined"));

        assertThatThrownBy(() -> service.capture(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
        verifyNoInteractions(writer);
    }

    @Test
    void refund_pendingPayment_throwsPay5006() {
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFUND_INVALID_STATE.getCode()));
        verifyNoInteractions(provider);
    }

    @Test
    void refund_capturedPayment_callsProvider() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-2"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE);

        verify(provider).refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, null);
        verify(writer).save(captured);
        verify(writer).publishLifecycleEvent(captured, "payment.refunded.v1");
        assertThat(result.id()).isEqualTo(PAYMENT_ID);
        assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(captured.getPreviousStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void refund_capturedPayment_passesTheStoredProviderIntentIdToTheOwningProvider() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        captured.setProviderIntentId("pi_stored_1");
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, "pi_stored_1"))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-3"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE);

        verify(provider).refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, "pi_stored_1");
        assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refund_capturedPayment_handsTheCurrencyOfTheRefundedPaymentToTheProvider() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        captured.setCurrency("VND");
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, "VND", REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-currency"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refund(PAYMENT_ID, AMOUNT, "VND", REFUND_REFERENCE);

        verify(provider).refund(PAYMENT_ID, AMOUNT, "VND", REFUND_KEY, null);
    }

    @Test
    void refund_providerRejected_throwsPaymentProviderRejected() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.rejected("refund_failed"));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
        verifyNoInteractions(writer);
    }

    @Test
    void refund_providerIndeterminate_throwsRetryableErrorAndLeavesThePaymentCaptured() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.indeterminate("stripe-timeout"));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException failure = (BusinessException) e;
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verifyNoInteractions(writer);
    }

    @Test
    void refund_withAnUnsupportedCurrency_isRejectedBeforeTheProviderIsCalled() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID, AMOUNT, "EUR", REFUND_REFERENCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CURRENCY_UNSUPPORTED.getCode()));
        verifyNoInteractions(provider);
    }

    @Test
    void thePaymentLookupThatGuardsTheRefundIsTakenUnderAWriteLock() throws Exception {
        Method method = PaymentRepository.class.getMethod("findByIdForUpdate", UUID.class);

        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void refund_locksThePaymentRowBeforeItChecksPaysAndWrites() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-lock"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE);

        verify(repository).findByIdForUpdate(PAYMENT_ID);
        verify(repository, never()).findById(PAYMENT_ID);
    }

    @Test
    void refund_keysTheProviderCallOnTheApprovalReferenceAndNotOnTheAmount() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(eq(PAYMENT_ID), eq(AMOUNT), eq(CURRENCY), any(), isNull()))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-key"));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(provider).refund(eq(PAYMENT_ID), eq(AMOUNT), eq(CURRENCY), key.capture(), isNull());
        assertThat(key.getValue()).isEqualTo(REFUND_KEY);
        assertThat(key.getValue()).doesNotContain(AMOUNT.toPlainString());
        assertThat(key.getValue()).doesNotContain(IDEMPOTENCY_KEY);
    }

    @Test
    void twoRefundsOfTheSameAmountOnOnePaymentUseDifferentProviderKeysAndBothAreRecorded() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        BigDecimal refundAmount = new BigDecimal("49.00");
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, refundAmount, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-first"));
        when(provider.refund(PAYMENT_ID, refundAmount, CURRENCY, "refund-" + OTHER_REFUND_REFERENCE, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-second"));
        when(refundRepository.findByPaymentId(PAYMENT_ID)).thenReturn(List.of());
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refund(PAYMENT_ID, refundAmount, CURRENCY, REFUND_REFERENCE);

        List<PaymentRefund> recorded = List.of(confirmedRefund(REFUND_REFERENCE, refundAmount));
        when(refundRepository.findByPaymentId(PAYMENT_ID)).thenReturn(recorded);
        captured.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        captured.setRefundedAmount(refundAmount);

        service.refund(PAYMENT_ID, refundAmount, CURRENCY, OTHER_REFUND_REFERENCE);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(provider, times(2)).refund(eq(PAYMENT_ID), eq(refundAmount), eq(CURRENCY),
                key.capture(), isNull());
        assertThat(key.getAllValues()).containsExactly(REFUND_KEY, "refund-" + OTHER_REFUND_REFERENCE);
        assertThat(key.getAllValues()).doesNotHaveDuplicates();
        ArgumentCaptor<PaymentRefund> ledger = ArgumentCaptor.forClass(PaymentRefund.class);
        verify(refundRepository, times(2)).save(ledger.capture());
        assertThat(ledger.getAllValues()).extracting(PaymentRefund::getRefundReference)
                .containsExactly(REFUND_REFERENCE.toString(), OTHER_REFUND_REFERENCE.toString());
        assertThat(captured.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("98.00"));
    }

    @Test
    void theSameApprovalIsRefundedOnlyOnce_evenWhenTheProviderReplaysTheRefund() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));
        when(refundRepository.findByPaymentIdAndRefundReference(PAYMENT_ID, REFUND_REFERENCE.toString()))
                .thenReturn(Optional.of(confirmedRefund(REFUND_REFERENCE, AMOUNT)));

        PaymentResponse result = service.refund(PAYMENT_ID, AMOUNT, CURRENCY, REFUND_REFERENCE);

        assertThat(result.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(provider);
        verifyNoInteractions(writer);
        verify(refundRepository, never()).save(any(PaymentRefund.class));
    }

    @Test
    void refund_ofAPartiallyRefundedPaymentIsAcceptedAndTheConfirmedTotalIsReconciled() {
        Payment partial = payment(PaymentStatus.PARTIALLY_REFUNDED);
        partial.setRefundedAmount(new BigDecimal("30.00"));
        BigDecimal secondAmount = new BigDecimal("20.00");
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(partial));
        when(provider.name()).thenReturn("mock");
        when(provider.refund(PAYMENT_ID, secondAmount, CURRENCY, REFUND_KEY, null))
                .thenReturn(PaymentProvider.ProviderResult.confirmed("mock-evt-second-partial"));
        when(refundRepository.findByPaymentId(PAYMENT_ID))
                .thenReturn(List.of(confirmedRefund(OTHER_REFUND_REFERENCE, new BigDecimal("30.00"))));
        when(writer.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse result = service.refund(PAYMENT_ID, secondAmount, CURRENCY, REFUND_REFERENCE);

        assertThat(result.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(result.previousStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(result.refundedAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void refund_aboveTheRemainingRefundableAmount_isRejected() {
        Payment captured = payment(PaymentStatus.CAPTURED);
        when(repository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID, new BigDecimal("500.00"), CURRENCY, REFUND_REFERENCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode()));
        verifyNoInteractions(provider);
    }

    @Test
    void findAllByOrderId_nullOrderId_delegatesToUnfilteredNewestFirstFinder() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(repository.findAllByOrderByCreatedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(payment(PaymentStatus.CAPTURED))));

        Page<PaymentResponse> page = service.findAllByOrderId(null, pageable);

        verify(repository).findAllByOrderByCreatedAtDesc(pageable);
        verify(repository, never()).findAllByOrderIdOrderByCreatedAtDesc(any(), any());
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(PAYMENT_ID);
        assertThat(page.getContent().get(0).orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void findAllByOrderId_withOrderId_delegatesToDerivedFinder() {
        PageRequest pageable = PageRequest.of(1, 5);
        when(repository.findAllByOrderIdOrderByCreatedAtDesc(ORDER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(payment(PaymentStatus.CAPTURED))));

        Page<PaymentResponse> page = service.findAllByOrderId(ORDER_ID, pageable);

        verify(repository).findAllByOrderIdOrderByCreatedAtDesc(ORDER_ID, pageable);
        verify(repository, never()).findAllByOrderByCreatedAtDesc(any());
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void findById_existingPayment_mapsEntityToResponse() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));

        PaymentResponse response = service.findById(PAYMENT_ID);

        assertThat(response.id()).isEqualTo(PAYMENT_ID);
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(response.currency()).isEqualTo(CURRENCY);
        assertThat(response.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.receiptKey()).isNull();
    }

    @Test
    void findById_unknownPayment_throwsPay5002() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND.getCode()));
    }

    @Test
    void findRefundableByOrderId_matchesACapturedAndAPartiallyRefundedPayment() {
        Payment partial = payment(PaymentStatus.PARTIALLY_REFUNDED);
        when(repository.findRefundableByOrderId(eq(ORDER_ID), any()))
                .thenReturn(List.of(partial));

        var found = service.findRefundableByOrderId(ORDER_ID);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(PAYMENT_ID);
        assertThat(found.get().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }
}
