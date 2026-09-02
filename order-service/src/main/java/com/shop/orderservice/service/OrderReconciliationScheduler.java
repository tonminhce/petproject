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
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Reconciliation scheduler for stuck PENDING orders (hardening §6, D8).
 * A PENDING order older than {@code order.reconciliation.stuck-minutes} means its
 * confirm orchestration died mid-flight. The reservation ledger is polled and the
 * order finalized without re-running the commit orchestration:
 *
 * <ul>
 *   <li>all applicable reservations COMMITTED → auto-confirm (AUTO_CONFIRMED_BY_RECON)</li>
 *   <li>all applicable reservations terminal (RELEASED/EXPIRED) → auto-cancel
 *       (nothing to release — already terminal)</li>
 *   <li>mixed — or ANY state-poll failure (404→RESERVATION_NOT_FOUND, promotion 4xx→
 *       SERVICE_UNAVAILABLE fallback) → NO automatic decision (spec §6): mixed counter +
 *       structured log for alerting; a missing reservation row inside the 30-min TTL
 *       window is inconsistent data that needs human eyes</li>
 * </ul>
 *
 * <p>Null reservation ids (no promotion reservation, legacy item without stock
 * reservation) are not-applicable — skipped, never mixed (D5 analog). Mutations set
 * fields directly (like {@code transitionStatus}) and go through orderRepository.save +
 * OrderEventPublisher inside a per-order transaction (order row + outbox event commit
 * atomically) — deliberately NOT through confirmOrder/cancelOrder, which would re-run
 * the commit orchestration and recurse. Races with a concurrent admin transition are
 * caught by the Order {@code @Version} guard; the losing write rolls back and the
 * order is re-examined next cycle.</p>
 *
 * <h3>H38 — bounded chunking + parallel fan-out</h3>
 * The pre-fix design loaded ALL stuck orders in a single unbounded
 * {@code findByStatusAndCreatedAtBefore(...)} and processed them serially —
 * one poll + one HTTP per order. A backlog of 1000 orders held the scheduler
 * thread for 1000 round-trips. The fix loads in chunks of
 * {@code order.reconciliation.batch-size} (default 50) and fans the per-order
 * reconcile across an executor — N orders complete in roughly the slowest
 * single remote poll, not the sum. The chunk size caps memory; the fan-out
 * caps wall time.
 */
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

    public OrderReconciliationScheduler(OrderRepository orderRepository,
                                        OrderItemRepository orderItemRepository,
                                        PromotionServiceClient promotionClient,
                                        InventoryServiceClient inventoryClient,
                                        OrderEventPublisher orderEventPublisher,
                                        OrderConfirmMetrics confirmMetrics,
                                        TransactionTemplate transactionTemplate,
                                        @Value("${order.reconciliation.stuck-minutes:30}") long stuckMinutes,
                                        @Value("${order.reconciliation.batch-size:50}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.promotionClient = promotionClient;
        this.inventoryClient = inventoryClient;
        this.orderEventPublisher = orderEventPublisher;
        this.confirmMetrics = confirmMetrics;
        this.transactionTemplate = transactionTemplate;
        this.stuckMinutes = stuckMinutes;
        this.batchSize = batchSize;
    }

    /** Gauge must be registered exactly once — supplier re-reads on every scrape. */
    @PostConstruct
    public void registerStuckGauge() {
        confirmMetrics.stuckGauge(this::stuckPendingCount);
    }

    @Scheduled(fixedDelayString = "${order.reconciliation.interval-ms:300000}")
    public void reconcileStuckOrders() {
        Instant cutoff = stuckCutoff();
        // H38 — bounded chunk: load up to batchSize stuck orders per pass. A
        // backlog of 1000 orders is processed as 20 successive batches (each
        // tick); a scheduler tick that finds >batchSize orders leaves the rest
        // for the next tick. The repository overload takes Pageable so the
        // SQL LIMIT is set in the DB layer (cheap, no over-fetch).
        List<Order> candidates = orderRepository.findByStatusAndCreatedAtBefore(
            OrderStatus.PENDING, cutoff,
            org.springframework.data.domain.PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return;
        log.info("RECONCILIATION_SCAN candidates={} batch_size={} cutoff={}",
            candidates.size(), batchSize, cutoff);

        // H38 — fan-out per-order reconcile in parallel so a 50-order chunk
        // completes in ~slowest poll rather than sum. The catch below isolates
        // one poisoned order — the rest of the chunk keeps its progress.
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
        if (states == null) return;  // poll failure → already routed to the mixed path
        if (states.isEmpty()) {      // nothing verifiable → never auto-decide (D8)
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

    /**
     * Polls promotion (when the order has a reservation) + per-item inventory states.
     * Returns {@code null} when any poll fails — the failure is routed to the mixed
     * path here because the caller must never auto-decide on incomplete data (§6).
     * Catches {@link Exception} (not just BusinessException) so transport failures
     * (ResourceAccessException from 5xx / connect-read timeouts, RestClientException)
     * also reach the mixed/alert path instead of the sweep-loop RECONCILIATION_ERROR
     * catch — alerting must stay loud during an upstream outage.
     */
    private List<String> pollReservationStates(Order order) {
        List<String> states = new ArrayList<>();
        try {
            if (order.getPromotionReservationId() != null) {
                states.add(promotionClient.getReservationState(order.getPromotionReservationId()).status());
            }
            for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
                if (item.getReservationId() != null) {  // D5 analog — legacy items skipped
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
        transactionTemplate.executeWithoutResult(tx -> {
            order.setCancelledAt(Instant.now());
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            orderEventPublisher.publishCancelled(order);
        });
        log.info("AUTO_CANCELLED_BY_RECON order={}", order.getId());
    }

    private void routeToMixed(Order order, List<String> states, String reason) {
        confirmMetrics.reconciliationMixed();
        log.warn("RECON_MIXED order={} reason={} states={} — no automatic action (needs investigation)",
            order.getId(), reason, states);
    }

    private Instant stuckCutoff() {
        return Instant.now().minus(Duration.ofMinutes(stuckMinutes));
    }

    private long stuckPendingCount() {
        return orderRepository.countByStatusAndCreatedAtBefore(OrderStatus.PENDING, stuckCutoff());
    }
}
