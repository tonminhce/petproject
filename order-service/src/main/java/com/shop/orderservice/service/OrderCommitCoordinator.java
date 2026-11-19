package com.shop.orderservice.service;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCommitCoordinator {

    private final PromotionServiceClient promotionClient;
    private final InventoryServiceClient inventoryClient;
    private final MeterRegistry meterRegistry;
    private final OrderConfirmMetrics confirmMetrics;
    private final CompensationFailureService compensationFailureService;

    private static final ExecutorService COMMIT_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "commit-coordinator-fanout");
                t.setDaemon(true);
                return t;
            });

    public CommitOutcome commitForConfirm(Order order, List<OrderItem> items) {
        List<CompensationTarget> committed = new ArrayList<>();
        try {
            if (order.getPromotionReservationId() != null) {
                Timer.Sample promotionTimer = Timer.start(meterRegistry);
                try {
                    promotionClient.commit(order.getPromotionReservationId());
                } finally {
                    promotionTimer.stop(confirmMetrics.timer("commit_promotion"));
                }
                committed.add(new CompensationTarget(CompensationTarget.Type.PROMOTION,
                    order.getPromotionReservationId()));
            }
            Timer.Sample inventoryTimer = Timer.start(meterRegistry);
            try {
                List<OrderItem> sorted = items.stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId)).toList();

                ConcurrentLinkedQueue<RuntimeException> failures = new ConcurrentLinkedQueue<>();
                Map<String, String> mdcContext = MDC.getCopyOfContextMap();
                List<CompletableFuture<Void>> commitFutures = new ArrayList<>(sorted.size());
                for (OrderItem item : sorted) {
                    if (item.getReservationId() == null) {
                        log.info("Order {} item {}: no reservationId (legacy) — skipping commit",
                            order.getId(), item.getId());
                        continue;
                    }
                    UUID reservationId = item.getReservationId();
                    CompletableFuture<Void> future = CompletableFuture
                        .runAsync(() -> {
                            Map<String, String> prev = MDC.getCopyOfContextMap();
                            if (mdcContext != null) {
                                MDC.setContextMap(mdcContext);
                            }
                            try {
                                inventoryClient.commit(reservationId);
                            } finally {
                                if (prev != null) {
                                MDC.setContextMap(prev);
                                } else {
                                    MDC.clear();
                                }
                            }
                        }, COMMIT_EXECUTOR)
                        .handle((v, ex) -> {
                            if (ex != null) {
                                Throwable cause = ex;
                                if (cause instanceof CompletionException && cause.getCause() != null) {
                                    cause = cause.getCause();
                                }
                                if (cause instanceof RuntimeException re) {
                                    failures.add(re);
                                } else {
                                    failures.add(new IllegalStateException("Inventory commit failed", cause));
                                }
                                return null;
                            }
                            synchronized (committed) {
                                committed.add(new CompensationTarget(
                                    CompensationTarget.Type.INVENTORY, reservationId));
                            }
                            return null;
                        });
                    commitFutures.add(future);
                }
                CompletableFuture.allOf(commitFutures.toArray(new CompletableFuture[0])).join();
                RuntimeException firstFailure = failures.poll();
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } finally {
                inventoryTimer.stop(confirmMetrics.timer("commit_inventory"));
            }
            meterRegistry.counter("order.confirm.commit.outcome", "result", "success").increment();
            return CommitOutcome.SUCCESS;
        } catch (RuntimeException ex) {
            log.warn("Order {} commit failed after {} successes — compensating",
                order.getId(), committed.size(), ex);
            compensateInReverse(order, committed);
            meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").increment();
            throw ex;
        }
    }

    private void compensateInReverse(Order order, List<CompensationTarget> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {
            CompensationTarget t = committed.get(i);
            try {
                if (t.type() == CompensationTarget.Type.PROMOTION) promotionClient.releaseCommitted(t.id());
                else inventoryClient.releaseCommitted(t.id());
            } catch (Exception ex) {
                compensationFailureService.record(order.getId(), t.id(), compensationKind(t), ex.getMessage());
                log.error("Failed to rollback {} reservation {} — recorded for the retry", t.type(), t.id(), ex);
                meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").increment();
            }
        }
    }

    private static String compensationKind(CompensationTarget target) {
        return target.type() == CompensationTarget.Type.PROMOTION
            ? FailedCompensation.KIND_PROMOTION_RELEASE_COMMITTED
            : FailedCompensation.KIND_RESERVATION_RELEASE_COMMITTED;
    }
}
