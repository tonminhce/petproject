package com.shop.notificationservice.scheduler;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationDeliveryService;
import com.shop.notificationservice.service.NotificationRetryPolicy;
import com.shop.notificationservice.service.NotificationWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class NotificationRetryScheduler {

    private final NotificationRepository repository;
    private final NotificationWriter writer;
    private final NotificationDeliveryService delivery;
    private final NotificationRetryPolicy retryPolicy;
    private final TransactionTemplate transactionTemplate;
    private final long staleSendingSeconds;
    private final int batchSize;

    public NotificationRetryScheduler(NotificationRepository repository,
                                      NotificationWriter writer,
                                      NotificationDeliveryService delivery,
                                      NotificationRetryPolicy retryPolicy,
                                      PlatformTransactionManager txManager,
                                      @Value("${shop.notification.retry.stale-sending-seconds:900}")
                                      long staleSendingSeconds,
                                      @Value("${shop.notification.retry.batch-size:50}")
                                      int batchSize) {
        this.repository = repository;
        this.writer = writer;
        this.delivery = delivery;
        this.retryPolicy = retryPolicy;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.staleSendingSeconds = staleSendingSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${shop.notification.retry.poll-ms:60000}",
            initialDelayString = "${shop.notification.retry.initial-delay-ms:0}")
    public void poll() {
        poll(Instant.now());
    }

    void poll(Instant now) {
        List<Notification> batch = claim(now);
        if (batch.isEmpty()) {
            return;
        }
        log.info("Retrying {} notification(s)", batch.size());
        for (Notification n : batch) {
            if (retryPolicy.isExhausted(n.getRetryCount())) {
                writer.markPermanent(n.getId(), "Exceeded max attempts=" + retryPolicy.maxAttempts());
                log.warn("Notification {} permanently failed after {} attempts",
                        n.getId(), n.getRetryCount());
            } else {
                delivery.deliver(n.getId());
            }
        }
    }

    private List<Notification> claim(Instant now) {
        return transactionTemplate.execute(status -> {
            List<Notification> candidates = repository.findRetryCandidates(
                    now,
                    List.of(NotificationStatus.PENDING, NotificationStatus.SENDING,
                            NotificationStatus.FAILED_RETRYABLE),
                    NotificationStatus.FAILED,
                    PageRequest.of(0, batchSize));
            for (Notification n : candidates) {
                n.setStatus(NotificationStatus.SENDING);
                n.setNextRetryAt(now.plusSeconds(staleSendingSeconds));
                repository.save(n);
            }
            return candidates;
        });
    }
}
