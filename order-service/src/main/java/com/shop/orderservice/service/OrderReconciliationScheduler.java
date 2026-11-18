package com.shop.orderservice.service;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
@Slf4j
public class OrderReconciliationScheduler {

    private static final String COMMITTED = "COMMITTED";
    private static final String RELEASED = "RELEASED";
    private static final String EXPIRED = "EXPIRED";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PromotionServiceClient promotionClient;
    private final InventoryServiceClient inventoryClient;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderConfirmMetrics confirmMetrics;
    private final TransactionTemplate transactionTemplate;
    private final long stuckMinutes;
    private final int batchSize;

    private static final ExecutorService RECONCILE_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "reconciliation-fanout");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public OrderReconciliationScheduler(OrderRepository orderRepository,
                                        OrderItemRepository orderItemRepository,
                                        PromotionServiceClient promotionClient,
                                        InventoryServiceClient inventoryClient,
                                        OrderEventPublisher orderEventPublisher,
                                        OrderConfirmMetrics confirmMetrics,
                                        TransactionTemplate transactionTemplate,
                                        @Value("${order.reconciliation.stuck-minutes:30}") long stuckMinutes,
                                        @Value("${order.reconciliation.batch-size:50}") int batchSize) {
        this(orderRepository, orderItemRepository, promotionClient, inventoryClient,
             orderEventPublisher, confirmMetrics, transactionTemplate,
             stuckMinutes, batchSize, 10_000L);
    }

    @PostConstruct
    public void registerStuckGauge() {
        confirmMetrics.stuckGauge(this::stuckPendingCount);
    }

    void invalidateStuckCountCache() {
        stuckPendingCountCache.invalidateForTest();
    }

    @Scheduled(fixedDelayString = "${order.reconciliation.interval-ms:300000}")
    public void reconcileStuckOrders() {
        Instant cutoff = stuckCutoff();
        List<Order> candidates = orderRepository.findByStatusAndCreatedAtBefore(
            OrderStatus.PENDING, cutoff,
            PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return;
        log.info("RECONCILIATION_SCAN candidates={} batch_size={} cutoff={}",
            candidates.size(), batchSize, cutoff);

        List<CompletableFuture<Void>> futures = new ArrayList<>(candidates.size());
        for (Order order : candidates) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    reconcile(order);
                } catch (Exception ex) {
                    log.error("RECONCILIATION_ERROR order={} — retried next cycle",
                        order.getId(), ex);
                }
            }, RECONCILE_EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void reconcile(Order order) {
        List<String> states = pollReservationStates(order);
        if (states == null) return;
        if (states.isEmpty()) {

            if (hasNoOrderItems(order)) {
                autoCancel(order, "orphan order with no line items - the create saga never completed");
                return;
            }
            routeToMixed(order, states, "no applicable reservations");
            return;
        }
        boolean allCommitted = states.stream().allMatch(COMMITTED::equals);
        boolean allTerminal = states.stream().allMatch(s -> RELEASED.equals(s) || EXPIRED.equals(s));
        if (allCommitted) {
            autoConfirm(order);
        } else if (allTerminal) {
            autoCancel(order);
        } else {
            routeToMixed(order, states, "mixed reservation states");
        }
    }

    private boolean hasNoOrderItems(Order order) {
        return orderItemRepository.findByOrderId(order.getId()).isEmpty();
    }

    private List<String> pollReservationStates(Order order) {
        List<String> states = new ArrayList<>();
        try {
            if (order.getPromotionReservationId() != null) {
                states.add(promotionClient.getReservationState(order.getPromotionReservationId()).status());
            }
            for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
                if (item.getReservationId() != null) {
                    states.add(inventoryClient.getReservationState(item.getReservationId()).status());
                }
            }
        } catch (Exception ex) {
            routeToMixed(order, states, "state poll failed: " + ex.getMessage());
            return null;
        }
        return states;
    }

    private void autoConfirm(Order order) {
        transactionTemplate.executeWithoutResult(tx -> {
            order.setConfirmedAt(Instant.now());
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            orderEventPublisher.publishStatusChanged(order);
        });
        log.info("AUTO_CONFIRMED_BY_RECON order={}", order.getId());
    }

    private void autoCancel(Order order) {
        autoCancel(order, null);
    }

    private void autoCancel(Order order, String reason) {
        transactionTemplate.executeWithoutResult(tx -> {
            order.setCancelledAt(Instant.now());
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            orderEventPublisher.publishCancelled(order);
        });
        if (reason == null) {
            log.info("AUTO_CANCELLED_BY_RECON order={}", order.getId());
        } else {
            log.info("AUTO_CANCELLED_BY_RECON order={} reason={}", order.getId(), reason);
        }
    }

    private void routeToMixed(Order order, List<String> states, String reason) {
        confirmMetrics.reconciliationMixed();
        log.warn("RECON_MIXED order={} reason={} states={} — no automatic action (needs investigation)",
            order.getId(), reason, states);
    }

    private Instant stuckCutoff() {
        return Instant.now().minus(Duration.ofMinutes(stuckMinutes));
    }

    private static final class MemoizedCount {
        private final long ttlNanos;
        private final LongSupplier supplier;
        private volatile long lastValue;
        private volatile long lastUpdatedNanos;

        MemoizedCount(long ttlMillis, LongSupplier supplier) {
            this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
            this.supplier = supplier;
        }

        long get() {
            long now = System.nanoTime();
            if (now - lastUpdatedNanos < ttlNanos) {
                return lastValue;
            }
            synchronized (this) {
                if (now - lastUpdatedNanos < ttlNanos) {
                    return lastValue;
                }
                lastValue = supplier.getAsLong();
                lastUpdatedNanos = now;
                return lastValue;
            }
        }

        void invalidateForTest() {
            synchronized (this) {
                this.lastUpdatedNanos = 0L;
            }
        }
    }

    private final MemoizedCount stuckPendingCountCache;

    OrderReconciliationScheduler(OrderRepository orderRepository,
                                 OrderItemRepository orderItemRepository,
                                 PromotionServiceClient promotionClient,
                                 InventoryServiceClient inventoryClient,
                                 OrderEventPublisher orderEventPublisher,
                                 OrderConfirmMetrics confirmMetrics,
                                 TransactionTemplate transactionTemplate,
                                 long stuckMinutes,
                                 int batchSize,
                                 long gaugeTtlMillis) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.promotionClient = promotionClient;
        this.inventoryClient = inventoryClient;
        this.orderEventPublisher = orderEventPublisher;
        this.confirmMetrics = confirmMetrics;
        this.transactionTemplate = transactionTemplate;
        this.stuckMinutes = stuckMinutes;
        this.batchSize = batchSize;
        this.stuckPendingCountCache = new MemoizedCount(gaugeTtlMillis, this::countStuckPending);
    }

    private long stuckPendingCount() {
        return stuckPendingCountCache.get();
    }

    private long countStuckPending() {
        return orderRepository.countByStatusAndCreatedAtBefore(OrderStatus.PENDING, stuckCutoff());
    }
}
