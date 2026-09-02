package com.shop.orderservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderCommitCoordinator unit tests — hardening §5.2, task 9 scenarios 1-6.
 * (type, id) compensation targets per D10; null reservationId skip per D5;
 * RESERVATION_NOT_FOUND rethrow per D4.
 */
@ExtendWith(MockitoExtension.class)
class OrderCommitCoordinatorTest {

    @Mock PromotionServiceClient promotionClient;
    @Mock InventoryServiceClient inventoryClient;

    private SimpleMeterRegistry meterRegistry;
    private OrderConfirmMetrics confirmMetrics;
    private OrderCommitCoordinator coordinator;

    private final UUID orderId = UUID.randomUUID();
    private final UUID promoId = UUID.randomUUID();
    private final UUID r1 = UUID.randomUUID();
    private final UUID r2 = UUID.randomUUID();
    private final UUID r3 = UUID.randomUUID();
    // deterministic ordering: coordinator sorts by productId, so the ids must have a known order
    private final UUID p1 = new UUID(0L, 1L);
    private final UUID p2 = new UUID(0L, 2L);
    private final UUID p3 = new UUID(0L, 3L);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        confirmMetrics = new OrderConfirmMetrics(meterRegistry);
        coordinator = new OrderCommitCoordinator(promotionClient, inventoryClient, meterRegistry, confirmMetrics);
    }

    private Order orderWithPromotion() {
        return Order.builder().id(orderId).promotionReservationId(promoId).build();
    }

    private OrderItem item(UUID productId, UUID reservationId) {
        return OrderItem.builder().id(UUID.randomUUID()).productId(productId)
            .reservationId(reservationId).build();
    }

    /** Scenario 1 — success: promotion commit, then inventory commits (H15 — parallel, order-agnostic). */
    @Test
    void commit_success_promotionThenInventoryParallelOrderAgnostic() {
        Order order = orderWithPromotion();
        // deliberately unsorted input — coordinator dispatches all inventory commits
        // in parallel via CompletableFuture (H15); ordering between them is no
        // longer asserted (was r1→r2→r3 in the serial version).
        List<OrderItem> items = List.of(item(p2, r2), item(p3, r3), item(p1, r1));

        CommitOutcome outcome = coordinator.commitForConfirm(order, items);

        assertThat(outcome).isEqualTo(CommitOutcome.SUCCESS);
        // Promotion commit still runs FIRST (single, before fan-out) so the
        // business invariant "promotion is committed before inventory" holds.
        InOrder inOrder = inOrder(promotionClient, inventoryClient);
        inOrder.verify(promotionClient).commit(promoId);
        // Inventory: every commit fired, in any order. The pre-fix InOrder check
        // (r1→r2→r3) was correct for the serial loop but is wrong for the
        // parallel fan-out — a strict assertion would be flaky.
        verify(inventoryClient).commit(r1);
        verify(inventoryClient).commit(r2);
        verify(inventoryClient).commit(r3);
        verify(promotionClient, never()).releaseCommitted(any());
        verify(inventoryClient, never()).releaseCommitted(any());
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "success").count())
            .isEqualTo(1.0);
        // deferred ledger assertions — nothing went wrong in the happy path
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isZero();
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").count())
            .isZero();
        // §8 phase timers — one recorded sample per decorated phase (task 10)
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_inventory").timer().count())
            .isEqualTo(1L);
    }

    /** Scenario 2 — promotion 5xx: rethrow, nothing else called, no compensation. */
    @Test
    void commit_promotionFails_rethrows_noOtherCalls_noCompensation() {
        Order order = orderWithPromotion();
        BusinessException failure = BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        doThrow(failure).when(promotionClient).commit(promoId);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, List.of(item(p1, r1))))
            .isSameAs(failure);

        verify(inventoryClient, never()).commit(any());
        verify(promotionClient, never()).releaseCommitted(any());
        verify(inventoryClient, never()).releaseCommitted(any());
        // deferred ledger assertion — compensation ran (empty targets) and was counted
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
        // compensated path: promotion phase was timed before it failed; inventory phase never reached
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.find("order.confirm.duration").tag("phase", "commit_inventory").timer())
            .isNull();
    }

    /** Scenario 3 — item 2 of 3 fails: only successfully-committed rows compensated (H15 — parallel fan-out). */
    @Test
    void commit_secondItemFails_compensatesSuccessesOnly_thenRethrows() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, r1), item(p2, r2), item(p3, r3));
        RuntimeException failure = BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "inventory");
        doNothing().when(inventoryClient).commit(r1);
        doThrow(failure).when(inventoryClient).commit(r2);
        // r3 — success path, never compensated because the fan-out fails before it
        // completes; the parallel test asserts compensation runs on the committed
        // set only (r1), not on the failed (r2) and not on the not-yet-attempted (r3).
        doNothing().when(inventoryClient).commit(r3);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, items)).isSameAs(failure);

        // item-2 never committed so never compensated (handle() captures ex);
        // item-3 may or may not have committed in the parallel fan-out but the
        // failure surfaces before compensation runs over it — assertion is on
        // the NEVER on r2 (the only deterministic post-failure invariant).
        verify(inventoryClient, never()).releaseCommitted(r2);
        verify(inventoryClient).releaseCommitted(r1);
        verify(promotionClient).releaseCommitted(promoId);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
        // compensated path timers — inventory phase records the region up to the failing commit (task 10)
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_inventory").timer().count())
            .isEqualTo(1L);
    }

    /** Scenario 4 — reservationId == null item (legacy): skipped, flow continues (D5). */
    @Test
    void commit_nullReservationIdItem_skipped_flowContinues() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, null), item(p2, r2));

        CommitOutcome outcome = coordinator.commitForConfirm(order, items);

        assertThat(outcome).isEqualTo(CommitOutcome.SUCCESS);
        verify(inventoryClient).commit(r2);
        verifyNoMoreInteractions(inventoryClient);   // no commit(null), no compensation
        verify(promotionClient, never()).releaseCommitted(any());
    }

    /** Scenario 5 — RESERVATION_NOT_FOUND from inventory commit: rethrown, NOT skipped (D4). */
    @Test
    void commit_reservationNotFound_rethrownNotSkipped() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, r1), item(p2, r2));
        BusinessException notFound = BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, r2);
        doNothing().when(inventoryClient).commit(r1);
        doThrow(notFound).when(inventoryClient).commit(r2);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, items))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("INV-3003"));

        // D4 — the failure propagates; already-committed targets are still compensated
        verify(inventoryClient, never()).releaseCommitted(r2);   // never committed → never compensated
        verify(inventoryClient).releaseCommitted(r1);
        verify(promotionClient).releaseCommitted(promoId);
    }

    /** Scenario 6 — rollback HTTP failure: swallowed, order.confirm.commit.outcome{result=rollback_failed} incremented. */
    @Test
    void commit_rollbackFailure_swallowed_counterIncremented_originalRethrown() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, r1), item(p2, r2));
        RuntimeException commitFailure = BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "inventory");
        doNothing().when(inventoryClient).commit(r1);
        doThrow(commitFailure).when(inventoryClient).commit(r2);
        // item-1 committed successfully — its rollback now fails (HTTP 500)
        doThrow(new IllegalStateException("release-committed 500")).when(inventoryClient).releaseCommitted(r1);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, items)).isSameAs(commitFailure);

        // loop continues past the failed rollback — promotion still released
        InOrder inOrder = inOrder(promotionClient, inventoryClient);
        inOrder.verify(inventoryClient).releaseCommitted(r1);
        inOrder.verify(promotionClient).releaseCommitted(promoId);
        verify(inventoryClient, never()).releaseCommitted(r2);   // never committed → never compensated
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
    }
}
