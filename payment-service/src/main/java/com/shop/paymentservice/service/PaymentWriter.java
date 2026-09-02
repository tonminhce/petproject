package com.shop.paymentservice.service;

import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.outbox.PaymentEventPublisher;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentWriter {

    private final PaymentRepository repository;
    private final PaymentEventRepository eventRepository;
    private final PaymentEventPublisher publisher;

    @Transactional
    public Payment insert(Payment payment) {
        return repository.saveAndFlush(payment);
    }

    @Transactional
    public Payment saveAndFlush(Payment payment) {
        return repository.saveAndFlush(payment);
    }

    @Transactional
    public PaymentEvent insertEvent(PaymentEvent event) {
        return eventRepository.saveAndFlush(event);
    }

    @Transactional
    public PaymentEvent markEventFailed(PaymentEvent event) {
        event.setStatus(PaymentEvent.STATUS_FAILED);
        return eventRepository.saveAndFlush(event);
    }

    /**
     * C3 — generic event save used by the retry scheduler and the webhook entry
     * path. The caller is expected to have set all fields (status, retryCount,
     * nextRetryAt, lastError) before calling.
     */
    @Transactional
    public PaymentEvent saveEvent(PaymentEvent event) {
        return eventRepository.saveAndFlush(event);
    }

    @Transactional
    public Payment completeWithEvent(Payment payment, PaymentEvent event, String outboxEventType) {
        event.setStatus(PaymentEvent.STATUS_PROCESSED);
        eventRepository.saveAndFlush(event);
        Payment saved = repository.saveAndFlush(payment);
        publisher.publish(saved, outboxEventType);
        return saved;
    }
}
