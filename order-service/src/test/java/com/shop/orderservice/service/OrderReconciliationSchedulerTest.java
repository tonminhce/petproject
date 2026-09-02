package com.shop.orderservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.internal.ReservationStateResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderReconciliationScheduler unit tests — hardening §6, task 12 scenarios 1-5.
 * State-poll failure routes to the mixed/alert path — never auto-decide (spec §6,
 * T5 ledger pointer). "Failure" is ANY exception from the poll: BusinessException
 * (404→RESERVATION_NOT_FOUND, promotion 4xx→SERVICE_UNAVAILABLE fallback) and
 * transport errors (ResourceAccessException/RestClientException from 5xx or
 * timeouts — fix round 1). Null reservation ids are not-applicable (D5 analog),
 * never mixed. Empty applicable set → mixed (nothing verifiable — no automatic
 * decision, needs human eyes per D8).
 */
@ExtendWith(MockitoExtension.class)
class OrderReconciliationSchedulerTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock PromotionServiceClient promotionClient;
    @Mock InventoryServiceClient inventoryClient;
    @Mock OrderEventPublisher eventPublisher;

    private SimpleMeterRegistry meterRegistry;
    private OrderConfirmMetrics confirmMetrics;
    private OrderReconciliationScheduler scheduler;

    private final UUID orderId = UUID.randomUUID();
    private final UUID promoId = UUID.randomUUID();
    private final UUID r1 = UUID.randomUUID();
    private final UUID r2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        confirmMetrics = new OrderConfirmMetrics(meterRegistry);
        TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        scheduler = new OrderReconciliationScheduler(orderRepository, orderItemRepository,
            promotionClient, inventoryClient, eventPublisher, confirmMetrics, txTemplate, 30, 50);
        scheduler.registerStuckGauge();
    }

    private Order stuckOrder(UUID promotionReservationId) {
        return Order.builder().id(orderId).status(OrderStatus.PENDING)
            .promotionReservationId(promotionReservationId)
            .build();
    }

    private OrderItem item(UUID reservationId) {
        return OrderItem.builder().id(UUID.randomUUID()).productId(UUID.randomUUID())
            .reservationId(reservationId).build();
    }

    private void givenCandidates(Order order, OrderItem... items) {
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(items));
    }

    /** Scenario 1 — all applicable COMMITTED → CONFIRMED + confirmedAt + status-changed event. */
    @Test
    void allCommitted_confirmsOrder_publishesStatusChanged() {
        Order order = stuckOrder(promoId);
        givenCandidates(order, item(r1), item(r2));
        when(promotionClient.getReservationState(promoId)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r1)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r2)).thenReturn(new ReservationStateResponse("COMMITTED"));

        scheduler.reconcileStuckOrders();

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getValue().getConfirmedAt()).isNotNull();
        assertThat(saved.getValue().getCancelledAt()).isNull();
        verify(eventPublisher).publishStatusChanged(order);
        verify(eventPublisher, never()).publishCancelled(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isZero();
    }

    /** Scenario 2 — all terminal (RELEASED/EXPIRED) → CANCELLED + cancelled event, nothing released. */
    @Test
    void allTerminal_cancelsOrder_publishesCancelled() {
        Order order = stuckOrder(promoId);
        givenCandidates(order, item(r1), item(r2));
        when(promotionClient.getReservationState(promoId)).thenReturn(new ReservationStateResponse("RELEASED"));
        when(inventoryClient.getReservationState(r1)).thenReturn(new ReservationStateResponse("EXPIRED"));
        when(inventoryClient.getReservationState(r2)).thenReturn(new ReservationStateResponse("RELEASED"));

        scheduler.reconcileStuckOrders();

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getValue().getCancelledAt()).isNotNull();
        verify(eventPublisher).publishCancelled(order);
        verify(eventPublisher, never()).publishStatusChanged(any());
        // terminal already — reconciliation must not call release/commit on either client
        verify(promotionClient, never()).release(any());
        verify(inventoryClient, never()).release(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isZero();
    }

    /** Scenario 3 — mixed states → untouched + order.reconciliation.mixed counter. */
    @Test
    void mixed_leavesOrderUntouched_incrementsMixedCounter() {
        Order order = stuckOrder(promoId);
        givenCandidates(order, item(r1), item(r2));
        when(promotionClient.getReservationState(promoId)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r1)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r2)).thenReturn(new ReservationStateResponse("PENDING"));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishStatusChanged(any());
        verify(eventPublisher, never()).publishCancelled(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    /** Scenario 4a — inventory state-poll 404 (INV-3003) → mixed path, never auto-decide. */
    @Test
    void inventoryStatePoll404_routesToMixed_neverDecides() {
        Order order = stuckOrder(promoId);
        givenCandidates(order, item(r1));
        when(promotionClient.getReservationState(promoId)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r1))
            .thenThrow(BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, r1));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishStatusChanged(any());
        verify(eventPublisher, never()).publishCancelled(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
    }

    /** Scenario 4c — inventory transport failure (5xx/timeout → ResourceAccessException) → mixed path. */
    @Test
    void inventoryStatePollTransportFailure_routesToMixed_neverDecides() {
        Order order = stuckOrder(promoId);
        givenCandidates(order, item(r1));
        when(promotionClient.getReservationState(promoId)).thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(r1))
            .thenThrow(new ResourceAccessException("connection reset"));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishStatusChanged(any());
        verify(eventPublisher, never()).publishCancelled(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    /** Scenario 4b — promotion state-poll 4xx (client maps to SERVICE_UNAVAILABLE) → mixed path. */
    @Test
    void promotionStatePoll4xx_serviceUnavailable_routesToMixed() {
        Order order = stuckOrder(promoId);
        // promotion is polled before items — neither item repo nor inventory is reached
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of(order));
        when(promotionClient.getReservationState(promoId))
            .thenThrow(BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion"));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishStatusChanged(any());
        verify(eventPublisher, never()).publishCancelled(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
    }

    /** Scenario 5 — recent PENDING orders are excluded by the cutoff passed to the finder. */
    @Test
    void usesStuckMinutesCutoff_andEmptyCandidatesPollNothing() {
        Instant before = Instant.now();
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of());

        scheduler.reconcileStuckOrders();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), cutoff.capture(), any(org.springframework.data.domain.Pageable.class));
        // cutoff ≈ now - 30 min (stuckMinutes=30), computed between `before` and the assertion
        Instant expectedFloor = before.minusSeconds(30 * 60 + 5);
        Instant expectedCeiling = Instant.now().minusSeconds(30 * 60 - 5);
        assertThat(cutoff.getValue()).isBetween(expectedFloor, expectedCeiling);
        verifyNoInteractions(promotionClient, inventoryClient, eventPublisher, orderItemRepository);
    }

    /** D5 analog — null promotionReservationId / null item reservationIds are not-applicable. */
    @Test
    void nullReservationIds_skipped_notMixed_confirmsOnRemainingApplicable() {
        Order order = stuckOrder(null);           // no promotion reservation
        givenCandidates(order, item(null), item(r1));  // first item legacy (no reservation)
        when(inventoryClient.getReservationState(r1)).thenReturn(new ReservationStateResponse("COMMITTED"));

        scheduler.reconcileStuckOrders();

        verify(promotionClient, never()).getReservationState(any());
        verify(inventoryClient).getReservationState(r1);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isZero();
    }

    /** Empty applicable set (nothing verifiable) → no automatic decision, mixed/alert path. */
    @Test
    void noApplicableReservations_routesToMixed() {
        Order order = stuckOrder(null);
        givenCandidates(order, item(null));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, never()).save(any());
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
    }

    /** Gauge registered exactly once at construction; supplier re-reads per scrape. */
    @Test
    void stuckGauge_registeredOnce_reflectsCount() {
        assertThat(meterRegistry.find("order.commit.stuck").gauge()).isNotNull();
        when(orderRepository.countByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class)))
            .thenReturn(3L);
        scheduler.invalidateStuckCountCache();
        assertThat(meterRegistry.get("order.commit.stuck").gauge().value()).isEqualTo(3.0);
        when(orderRepository.countByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class)))
            .thenReturn(1L);
        scheduler.invalidateStuckCountCache();
        assertThat(meterRegistry.get("order.commit.stuck").gauge().value()).isEqualTo(1.0);
        scheduler.registerStuckGauge();  // second call must not duplicate the meter
        assertThat(meterRegistry.get("order.commit.stuck").gauges().size()).isEqualTo(1);
    }

    /**
     * One candidate throwing must not abort the sweep. The item-repo hiccup happens
     * inside the poll — since fix round 1 ANY poll failure (incl. unexpected ones)
     * routes to the mixed path, so both candidates land there; the sweep-loop outer
     * catch remains for failures outside the poll (tx/event publish).
     */
    @Test
    void unexpectedErrorOnOneOrder_doesNotAbortSweep() {
        Order bad = Order.builder().id(UUID.randomUUID()).status(OrderStatus.PENDING).build();
        Order good = stuckOrder(null);
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of(bad, good));
        when(orderItemRepository.findByOrderId(bad.getId())).thenThrow(new IllegalStateException("db hiccup"));
        when(orderItemRepository.findByOrderId(good.getId())).thenReturn(List.of(item(null)));

        assertThatCode(scheduler::reconcileStuckOrders).doesNotThrowAnyException();
        // both orders processed — neither auto-decided, both routed to mixed/alert
        assertThat(meterRegistry.counter("order.reconciliation.mixed").count()).isEqualTo(2.0);
        verify(orderRepository, never()).save(any());
    }
}
