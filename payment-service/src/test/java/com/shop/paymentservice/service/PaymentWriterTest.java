package com.shop.paymentservice.service;

import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.outbox.PaymentEventPublisher;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWriterTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventRepository eventRepository;
    @Mock PaymentEventPublisher publisher;

    private PaymentWriter writer;

    @BeforeEach
    void setUp() {
        writer = new PaymentWriter(paymentRepository, eventRepository, publisher);
    }

    private PaymentEvent event(String status) {
        return PaymentEvent.builder()
                .paymentId(PAYMENT_ID)
                .provider("mock")
                .providerEventId("evt_123")
                .type("payment.captured")
                .payload("{}")
                .status(status)
                .build();
    }

    @Test
    void insertEvent_flushesEventRow() {
        PaymentEvent inserted = event(PaymentEvent.STATUS_FAILED);
        when(eventRepository.saveAndFlush(inserted)).thenReturn(inserted);

        PaymentEvent result = writer.insertEvent(inserted);

        assertThat(result).isSameAs(inserted);
        verify(eventRepository).saveAndFlush(inserted);
    }

    @Test
    void markEventFailed_setsFailedStatus_andFlushes() {
        PaymentEvent processed = event(PaymentEvent.STATUS_PROCESSED);

        writer.markEventFailed(processed);

        assertThat(processed.getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED);
        verify(eventRepository).saveAndFlush(processed);
    }

    @Test
    void completeWithEvent_marksEventProcessed_savesPayment_andPublishesOutboxRowAtomically() {
        Payment payment = Payment.builder().id(PAYMENT_ID).build();
        PaymentEvent failed = event(PaymentEvent.STATUS_FAILED);

        writer.completeWithEvent(payment, failed, "payment.captured.v1");

        assertThat(failed.getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        verify(eventRepository).saveAndFlush(failed);
        verify(paymentRepository).saveAndFlush(payment);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(publisher).publish(captor.capture(), org.mockito.ArgumentMatchers.eq("payment.captured.v1"));
        assertThat(captor.getValue()).isSameAs(payment);
    }
}
