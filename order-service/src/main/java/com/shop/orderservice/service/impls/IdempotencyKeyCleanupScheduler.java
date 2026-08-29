package com.shop.orderservice.service.impls;

import com.shop.orderservice.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyKeyRepository repository;

    @Scheduled(cron = "${order.cleanup.idempotency-cron:0 0 4 * * *}")
    @Transactional
    public void purgeExpired() {
        try {
            int deleted = repository.deleteByExpiresAtBefore(Instant.now());
            if (deleted > 0) log.info("Purged {} expired idempotency keys", deleted);
        } catch (Exception ex) {
            log.error("Idempotency key purge failed", ex);
        }
    }
}
