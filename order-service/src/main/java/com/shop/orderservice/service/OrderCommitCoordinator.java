package com.shop.orderservice.service;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
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

/**
 * H15 — inventory commits in the confirm transaction used to run serially
 * (one HTTP round-trip per item, ~50ms each, ~1s for a 20-item order).
 * The promotion commit runs first; only after it succeeds do the inventory
 * commits start. Both branches are now parallelised via {@link CompletableFuture}
 * so the confirm wall time becomes the slowest single remote commit, not the
 * sum. The promotion commit still runs first because the inventory commits
 * carry promotion-derived pricing data — the order of business invariants
 * is preserved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCommitCoordinator {

    private final PromotionServiceClient promotionClient;
    private final InventoryServiceClient inventoryClient;
    private final MeterRegistry meterRegistry;
    private final OrderConfirmMetrics confirmMetrics;

    private static final ExecutorService COMMIT_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "commit-coordinator-fanout");
                t.setDaemon(true);
                return t;
            });

    /** SUCCESS or throws. Never PARTIAL — compensations are best-effort, failures counted. */
    public CommitOutcome commitForConfirm(Order order, List<OrderItem> items) {
        List<CompensationTarget> committed = new ArrayList<>();   // (type,id) pairs — spec D10
        try {
            if (order.getPromotionReservationId() != null) {
                Timer.Sample promotionTimer = Timer.start(meterRegistry);
                try {
                    promotionClient.commit(order.getPromotionReservationId());
                } finally {                                        // record even on failure — §8 latency alerting
                    promotionTimer.stop(confirmMetrics.timer("commit_promotion"));
                }
                committed.add(new CompensationTarget(CompensationTarget.Type.PROMOTION,
                    order.getPromotionReservationId()));
            }
            Timer.Sample inventoryTimer = Timer.start(meterRegistry);
            try {
                List<OrderItem> sorted = items.stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId)).toList();

                // H15 — fan inventory commits out in parallel. Each commit
                // becomes a CompletableFuture; the coordinator joins once
                // after all are dispatched. We track SUCCESSFUL commits
                // (handle() callback) so a failure doesn't poison the
                // compensation list — the failed row was never committed
                // and so must not be released-committed on rollback.
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
                                // unwrap and queue for rethrow below
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
                // Block until ALL dispatches complete (success or failure).
                CompletableFuture.allOf(commitFutures.toArray(new CompletableFuture[0])).join();
                // Surface the first failure so the outer catch compensates and rethrows.
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
            compensateInReverse(committed);
            meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").increment();
            throw ex;
        }
    }

    private void compensateInReverse(List<CompensationTarget> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {          // plain reverse loop — no Guava
            CompensationTarget t = committed.get(i);
            try {
                if (t.type() == CompensationTarget.Type.PROMOTION) promotionClient.releaseCommitted(t.id());
                else inventoryClient.releaseCommitted(t.id());
            } catch (Exception ex) {
                log.error("Failed to rollback {} reservation {} — reconciliation owns it", t.type(), t.id(), ex);
                meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").increment();
            }
        }
    }
}
