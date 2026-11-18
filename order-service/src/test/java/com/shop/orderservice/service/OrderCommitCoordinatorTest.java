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

    @Test
    void commit_success_promotionThenInventoryParallelOrderAgnostic() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p2, r2), item(p3, r3), item(p1, r1));

        CommitOutcome outcome = coordinator.commitForConfirm(order, items);

        assertThat(outcome).isEqualTo(CommitOutcome.SUCCESS);
        InOrder inOrder = inOrder(promotionClient, inventoryClient);
        inOrder.verify(promotionClient).commit(promoId);
        verify(inventoryClient).commit(r1);
        verify(inventoryClient).commit(r2);
        verify(inventoryClient).commit(r3);
        verify(promotionClient, never()).releaseCommitted(any());
        verify(inventoryClient, never()).releaseCommitted(any());
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "success").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isZero();
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").count())
            .isZero();
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_inventory").timer().count())
            .isEqualTo(1L);
    }

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
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.find("order.confirm.duration").tag("phase", "commit_inventory").timer())
            .isNull();
    }

    @Test
    void commit_secondItemFails_compensatesSuccessesOnly_thenRethrows() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, r1), item(p2, r2), item(p3, r3));
        RuntimeException failure = BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "inventory");
        doNothing().when(inventoryClient).commit(r1);
        doThrow(failure).when(inventoryClient).commit(r2);
        doNothing().when(inventoryClient).commit(r3);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, items)).isSameAs(failure);

        verify(inventoryClient, never()).releaseCommitted(r2);
        verify(inventoryClient).releaseCommitted(r1);
        verify(promotionClient).releaseCommitted(promoId);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_promotion").timer().count())
            .isEqualTo(1L);
        assertThat(meterRegistry.get("order.confirm.duration").tag("phase", "commit_inventory").timer().count())
            .isEqualTo(1L);
    }

    @Test
    void commit_nullReservationIdItem_skipped_flowContinues() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, null), item(p2, r2));

        CommitOutcome outcome = coordinator.commitForConfirm(order, items);

        assertThat(outcome).isEqualTo(CommitOutcome.SUCCESS);
        verify(inventoryClient).commit(r2);
        verifyNoMoreInteractions(inventoryClient);
        verify(promotionClient, never()).releaseCommitted(any());
    }

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

        verify(inventoryClient, never()).releaseCommitted(r2);
        verify(inventoryClient).releaseCommitted(r1);
        verify(promotionClient).releaseCommitted(promoId);
    }

    @Test
    void commit_rollbackFailure_swallowed_counterIncremented_originalRethrown() {
        Order order = orderWithPromotion();
        List<OrderItem> items = List.of(item(p1, r1), item(p2, r2));
        RuntimeException commitFailure = BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "inventory");
        doNothing().when(inventoryClient).commit(r1);
        doThrow(commitFailure).when(inventoryClient).commit(r2);
        doThrow(new IllegalStateException("release-committed 500")).when(inventoryClient).releaseCommitted(r1);

        assertThatThrownBy(() -> coordinator.commitForConfirm(order, items)).isSameAs(commitFailure);

        InOrder inOrder = inOrder(promotionClient, inventoryClient);
        inOrder.verify(inventoryClient).releaseCommitted(r1);
        inOrder.verify(promotionClient).releaseCommitted(promoId);
        verify(inventoryClient, never()).releaseCommitted(r2);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "rollback_failed").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").count())
            .isEqualTo(1.0);
    }
}
