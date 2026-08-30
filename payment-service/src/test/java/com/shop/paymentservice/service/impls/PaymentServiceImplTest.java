package com.shop.paymentservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String IDEMPOTENCY_KEY = "ord-1-pay-1";
    private static final BigDecimal AMOUNT = new BigDecimal("98.00");
    private static final String CURRENCY = "USD";

    @Mock PaymentRepository repository;
    @Mock PaymentWriter writer;
    @Mock PaymentProvider provider;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(repository, writer, provider);
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

    @Test
    void create_happyPath_insertsPendingPayment() {
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(provider.name()).thenReturn("mock");
        Payment saved = payment(PaymentStatus.PENDING);
        when(writer.insert(any(Payment.class))).thenReturn(saved);

        Payment result = service.create(request());

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).insert(captor.capture());
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

        Payment result = service.create(request());

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(writer);
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
        when(provider.capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY))
                .thenReturn(new PaymentProvider.ProviderResult("mock-evt-1", true));

        Payment result = service.capture(PAYMENT_ID);

        verify(provider).capture(PAYMENT_ID, AMOUNT, CURRENCY, IDEMPOTENCY_KEY);
        assertThat(result).isSameAs(pending);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(writer);
    }

    @Test
    void refund_pendingPayment_throwsPay5006() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatThrownBy(() -> service.refund(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFUND_INVALID_STATE.getCode()));
        verifyNoInteractions(provider);
    }

    @Test
    void refund_capturedPayment_callsProvider() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));
        when(provider.refund(PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY))
                .thenReturn(new PaymentProvider.ProviderResult("mock-evt-2", true));

        Payment result = service.refund(PAYMENT_ID);

        verify(provider).refund(PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verifyNoInteractions(writer);
    }

    // --- backoffice read API ---

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
}
