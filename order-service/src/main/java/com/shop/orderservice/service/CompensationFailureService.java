package com.shop.orderservice.service;

import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.config.CompensationProperties;
import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.repository.FailedCompensationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class CompensationFailureService {

    private static final Set<String> OPEN_STATUSES =
            Set.of(FailedCompensation.STATUS_PENDING, FailedCompensation.STATUS_PROCESSING);

    private static final Set<String> DISPATCHABLE_KINDS = Set.of(
            FailedCompensation.KIND_RESERVATION_RELEASE,
            FailedCompensation.KIND_RESERVATION_RELEASE_COMMITTED,
            FailedCompensation.KIND_PROMOTION_RELEASE_COMMITTED);

    private final FailedCompensationRepository repository;
    private final StockReservationService stockReservationService;
    private final PromotionServiceClient promotionClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate recordTransaction;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate updateTransaction;
    private final Duration retryBackoff;
    private final Duration claimLease;
    private final int maxAttempts;
    private final int batchSize;

    @Autowired
    public CompensationFailureService(FailedCompensationRepository repository,
                                      StockReservationService stockReservationService,
                                      PromotionServiceClient promotionClient,
                                      PlatformTransactionManager transactionManager,
                                      MeterRegistry meterRegistry,
                                      CompensationProperties properties) {
        this.repository = repository;
        this.stockReservationService = stockReservationService;
        this.promotionClient = promotionClient;
        this.meterRegistry = meterRegistry;
        this.retryBackoff = Duration.ofSeconds(properties.retryBackoffSeconds());
        this.claimLease = Duration.ofSeconds(properties.claimLeaseSeconds());
        this.maxAttempts = properties.maxAttempts();
        this.batchSize = properties.batchSize();
        this.recordTransaction = new TransactionTemplate(transactionManager);
        this.recordTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.updateTransaction = new TransactionTemplate(transactionManager);
        this.updateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(UUID orderId, UUID reservationId, String kind, String error) {
        if (reservationId == null) {
            return;
        }
        try {
            recordTransaction.executeWithoutResult(status -> insertLedgerRow(orderId, reservationId, kind, error));
        } catch (DataIntegrityViolationException ex) {
            log.warn("Compensation {} for reservation {} is already recorded as an open entry",
                    kind, reservationId);
        }
    }

    private void insertLedgerRow(UUID orderId, UUID reservationId, String kind, String error) {
        if (repository.existsByReservationIdAndKindAndStatusIn(reservationId, kind, OPEN_STATUSES)) {
            return;
        }
        Instant now = Instant.now();
        FailedCompensation row = FailedCompensation.builder()
                .orderId(orderId)
                .reservationId(reservationId)
                .kind(kind)
                .status(FailedCompensation.STATUS_PENDING)
                .attempts(0)
                .lastError(error)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.saveAndFlush(row);
        log.error("Compensation {} failed for reservation {} (order {}) — recorded for the retry: {}",
                kind, reservationId, orderId, error);
    }

    @Scheduled(fixedDelayString = "${order.compensation.retry-millis:30000}")
    public int retryPending() {
        Instant now = Instant.now();
        List<FailedCompensation> candidates = claimTransaction.execute(status ->
                repository.findDue(OPEN_STATUSES, now, PageRequest.of(0, batchSize)));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int resolved = 0;
        for (FailedCompensation candidate : candidates) {
            if (!claim(candidate.getId(), now)) {
                continue;
            }
            FailedCompensation row = repository.findById(candidate.getId()).orElse(null);
            if (row == null) {
                continue;
            }
            if (!DISPATCHABLE_KINDS.contains(row.getKind())) {
                exhaust(row.getId(), "unsupported compensation kind " + row.getKind(), now);
                continue;
            }
            try {
                dispatch(row.getKind(), row.getReservationId());
                resolve(row.getId(), now);
                resolved++;
            } catch (Exception ex) {
                fail(row.getId(), ex.getMessage(), now);
            }
        }
        return resolved;
    }

    private boolean claim(UUID id, Instant now) {
        Integer claimed = claimTransaction.execute(status -> repository.claimForProcessing(
                id, FailedCompensation.STATUS_PROCESSING, OPEN_STATUSES, now.plus(claimLease), now));
        return claimed != null && claimed > 0;
    }

    private void dispatch(String kind, UUID reservationId) {
        switch (kind) {
            case FailedCompensation.KIND_RESERVATION_RELEASE -> stockReservationService.release(reservationId);
            case FailedCompensation.KIND_RESERVATION_RELEASE_COMMITTED ->
                    stockReservationService.releaseCommitted(reservationId);
            case FailedCompensation.KIND_PROMOTION_RELEASE_COMMITTED ->
                    promotionClient.releaseCommitted(reservationId);
            default -> throw new IllegalArgumentException("unsupported compensation kind " + kind);
        }
    }

    private void resolve(UUID id, Instant now) {
        updateTransaction.executeWithoutResult(status -> {
            FailedCompensation row = repository.findById(id).orElse(null);
            if (row == null) {
                return;
            }
            row.setStatus(FailedCompensation.STATUS_RESOLVED);
            row.setResolvedAt(now);
            row.setNextAttemptAt(null);
            row.setUpdatedAt(now);
            repository.save(row);
            log.info("Compensation {} for reservation {} resolved", row.getKind(), row.getReservationId());
        });
    }

    private void fail(UUID id, String error, Instant now) {
        updateTransaction.executeWithoutResult(status -> {
            FailedCompensation row = repository.findById(id).orElse(null);
            if (row == null) {
                return;
            }
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(error);
            row.setUpdatedAt(now);
            if (attempts >= maxAttempts) {
                row.setStatus(FailedCompensation.STATUS_EXHAUSTED);
                row.setNextAttemptAt(null);
                repository.save(row);
                meterRegistry.counter("order.compensation.exhausted", "kind", row.getKind()).increment();
                log.error("Compensation {} for reservation {} exhausted after {} attempts — manual action required: {}",
                        row.getKind(), row.getReservationId(), attempts, error);
                return;
            }
            row.setStatus(FailedCompensation.STATUS_PENDING);
            row.setNextAttemptAt(now.plus(retryBackoff));
            repository.save(row);
            log.warn("Compensation {} for reservation {} still failing (attempt {}): {}",
                    row.getKind(), row.getReservationId(), attempts, error);
        });
    }

    private void exhaust(UUID id, String error, Instant now) {
        updateTransaction.executeWithoutResult(status -> {
            FailedCompensation row = repository.findById(id).orElse(null);
            if (row == null) {
                return;
            }
            row.setStatus(FailedCompensation.STATUS_EXHAUSTED);
            row.setNextAttemptAt(null);
            row.setLastError(error);
            row.setUpdatedAt(now);
            repository.save(row);
            meterRegistry.counter("order.compensation.exhausted", "kind", row.getKind()).increment();
            log.error("Compensation {} for reservation {} cannot be retried — manual action required: {}",
                    row.getKind(), row.getReservationId(), error);
        });
    }
}
