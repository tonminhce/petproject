package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.outbox.PaymentEventPublisher;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.webhook.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C3 contract: event rows are PERSISTED FIRST as FAILED_RETRYABLE (before the
 * payment transition is attempted), dedupe skips only PROCESSED rows, and any
 * processing failure keeps the row FAILED_RETRYABLE via
 * {@code writer.saveEvent} so the retry scheduler can replay it.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventServiceTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final BigDecimal AMOUNT = new BigDecimal("98.00");
    private static final String CURRENCY = "USD";
    private static final String PROVIDER = "mock";
    private static final String PROVIDER_EVENT_ID = "evt_123";

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventRepository eventRepository;
    @Mock PaymentWriter writer;
    @Mock PaymentEventPublisher publisher;
    @Mock ReceiptService receiptService;

    private WebhookEventService service;

    @BeforeEach
    void setUp() {
        service = new WebhookEventService(paymentRepository, eventRepository, writer, new ObjectMapper(), receiptService,
                new com.shop.paymentservice.config.PaymentStripeProperties("", "mock-webhook-secret", "2024-06-20", false));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            inv.getArgument(1, PaymentEvent.class).setStatus(PaymentEvent.STATUS_PROCESSED);
            return inv.getArgument(0, Payment.class);
        }).when(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
    }

    private Payment payment(PaymentStatus status) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .currency(CURRENCY)
                .status(status)
                .provider(PROVIDER)
                .idempotencyKey("ord-1-pay-1")
                .build();
    }

    private byte[] body(WebhookPayload payload) {
        try {
            return new ObjectMapper().writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WebhookPayload.WebhookPayloadBuilder capturedPayload() {
        return WebhookPayload.builder()
                .eventId("evt_123")
                .eventType("payment.captured")
                .paymentId(PAYMENT_ID.toString())
                .orderId(ORDER_ID.toString())
                .amount(AMOUNT)
                .currency(CURRENCY)
                .status("CAPTURED")
                .providerEventId(PROVIDER_EVENT_ID);
    }

    /**
     * C3: a fresh event id means no prior row — the service inserts one, then
     * RE-READS it (findFirst) before processing. This stub simulates that
     * persistence: empty on the dedupe probe, the stored row afterwards.
     */
    private void happyPathStubs() {
        dedupeStubsNoRow();
        AtomicReference<PaymentEvent> stored = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(0, PaymentEvent.class));
            return inv.getArgument(0, PaymentEvent.class);
        }).when(writer).insertEvent(any(PaymentEvent.class));
        when(eventRepository.findFirstByProviderAndProviderEventId(PROVIDER, PROVIDER_EVENT_ID))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
    }

    /** Dedupe probe only: no prior row, insert behavior left to the caller. */
    private void dedupeStubsNoRow() {
        when(eventRepository.findFirstByProviderAndProviderEventId(PROVIDER, PROVIDER_EVENT_ID))
                .thenReturn(Optional.empty());
    }

    private PaymentEvent processedEvent() {
        return PaymentEvent.builder()
                .paymentId(PAYMENT_ID)
                .provider(PROVIDER)
                .providerEventId(PROVIDER_EVENT_ID)
                .type("payment.captured")
                .payload("{}")
                .status(PaymentEvent.STATUS_PROCESSED)
                .retryCount(1)
                .build();
    }

    @Test
    void validCapturedEvent_transitionsState_recordsPreviousStatus_andOutboxRow() {
        happyPathStubs();
        Payment pending = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));

        service.handle(PROVIDER, body(capturedPayload().build()));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).completeWithEvent(paymentCaptor.capture(), eventCaptor.capture(), eq("payment.captured.v1"));
        Payment updated = paymentCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(updated.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        verify(writer).insertEvent(any(PaymentEvent.class));
    }

    @Test
    void capturedTransition_storesReceipt_andPersistsReceiptKey() {
        happyPathStubs();
        Payment pending = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(receiptService.storeReceipt(any(Payment.class))).thenReturn("receipts/" + PAYMENT_ID + ".json");

        service.handle(PROVIDER, body(capturedPayload().build()));

        verify(receiptService).storeReceipt(pending);
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getReceiptKey()).isEqualTo("receipts/" + PAYMENT_ID + ".json");
    }

    @Test
    void capturedTransition_receiptFailure_skipsFollowUpSave_andStillAcks() {
        happyPathStubs();
        Payment pending = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
        when(receiptService.storeReceipt(any(Payment.class))).thenReturn(null);

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        verify(receiptService).storeReceipt(pending);
        verify(writer, never()).saveAndFlush(any(Payment.class));
        assertThat(pending.getReceiptKey()).isNull();
        verify(writer, never()).saveEvent(any(PaymentEvent.class));
    }

    @Test
    void nonCapturedTransition_neverStoresReceipt() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        service.handle(PROVIDER, body(capturedPayload().status("FAILED").build()));

        verifyNoInteractions(receiptService);
    }

    @Test
    void processedEventDedupe_knownProviderEventId_isNoOp() {
        when(eventRepository.findFirstByProviderAndProviderEventId(PROVIDER, PROVIDER_EVENT_ID))
                .thenReturn(Optional.of(processedEvent()));

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        verifyNoInteractions(writer);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void duplicateInsertRace_dataIntegrityViolation_isNoOpAck() {
        dedupeStubsNoRow();
        when(writer.insertEvent(any(PaymentEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payment_events_provider_event"));

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void unknownPayment_marksEventFailedRetryable_andAcks() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> saveCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).insertEvent(any(PaymentEvent.class));
        verify(writer).saveEvent(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        assertThat(saveCaptor.getValue().getLastError()).isNotBlank();
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
    }

    @Test
    void amountMismatch_marksEventFailedRetryable_stateUnchanged() {
        happyPathStubs();
        Payment pending = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));

        assertThatCode(() -> service.handle(PROVIDER,
                body(capturedPayload().amount(new BigDecimal("999.00")).build())))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> saveCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).saveEvent(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.getPreviousStatus()).isNull();
    }

    @Test
    void illegalTransition_marksEventFailedRetryable_andAcks() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> saveCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).saveEvent(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
    }

    @Test
    void handlerThrow_marksEventFailedRetryable_noExceptionEscapes() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        org.mockito.Mockito.doThrow(new RuntimeException("kafka down"))
                .when(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().build())))
                .doesNotThrowAnyException();

        verify(writer).saveEvent(any(PaymentEvent.class));
    }

    @Test
    void failedStatusEvent_transitionsPaymentToFailed() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        service.handle(PROVIDER, body(capturedPayload().status("FAILED").build()));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).completeWithEvent(paymentCaptor.capture(), any(PaymentEvent.class), eq("payment.failed.v1"));
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentCaptor.getValue().getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void unparseablePoison_insertsFailedRetryableSyntheticEvent_acks_andRepeatsDedupe() {
        byte[] raw = "not json at all {{{".getBytes(StandardCharsets.UTF_8);
        when(eventRepository.findFirstByProviderAndProviderEventId(eq(PROVIDER), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(processedEvent()));

        assertThatCode(() -> service.handle(PROVIDER, raw)).doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).insertEvent(eventCaptor.capture());
        PaymentEvent inserted = eventCaptor.getValue();
        assertThat(inserted.getProviderEventId()).startsWith("unparseable-");
        assertThat(inserted.getProviderEventId()).hasSizeLessThanOrEqualTo(128);
        assertThat(inserted.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());

        assertThatCode(() -> service.handle(PROVIDER, raw)).doesNotThrowAnyException();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void nullStatus_insertsFailedRetryableEvent_noNpe() {
        dedupeStubsNoRow();

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().status(null).build())))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).insertEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void nullPaymentId_insertsFailedRetryableEvent_noNpe() {
        dedupeStubsNoRow();

        assertThatCode(() -> service.handle(PROVIDER, body(capturedPayload().paymentId(null).build())))
                .doesNotThrowAnyException();

        verify(writer).insertEvent(any(PaymentEvent.class));
        verify(writer, never()).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void amountScaleDifference_sameValue_isProcessed() {
        happyPathStubs();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        service.handle(PROVIDER, body(capturedPayload().amount(new BigDecimal("98.0")).build()));

        verify(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), eq("payment.captured.v1"));
    }
}
