package com.shop.orderservice.service;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderReconciliationSchedulerChunkedTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock PromotionServiceClient promotionClient;
    @Mock InventoryServiceClient inventoryClient;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock PlatformTransactionManager txManager;

    private OrderConfirmMetrics confirmMetrics;
    private OrderReconciliationScheduler scheduler;

    private static final int BATCH = 50;
    private static final int TOTAL_STUCK = 100;

    @BeforeEach
    void setUp() {
        confirmMetrics = new OrderConfirmMetrics(new SimpleMeterRegistry());
        when(txManager.getTransaction(any())).thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        scheduler = new OrderReconciliationScheduler(
            orderRepository, orderItemRepository, promotionClient, inventoryClient,
            orderEventPublisher, confirmMetrics, txTemplate,
             30L,  BATCH
        );
        ReflectionTestUtils.invokeMethod(scheduler, "registerStuckGauge");

        when(promotionClient.getReservationState(any(UUID.class)))
            .thenReturn(new ReservationStateResponse("COMMITTED"));
        when(inventoryClient.getReservationState(any(UUID.class)))
            .thenReturn(new ReservationStateResponse("COMMITTED"));
        when(orderItemRepository.findByOrderId(any(UUID.class)))
            .thenReturn(List.of(OrderItem.builder()
                .id(UUID.randomUUID()).orderId(UUID.randomUUID())
                .reservationId(UUID.randomUUID()).productId(UUID.randomUUID()).build()));
    }

    @Test
    void hundredStuckOrdersResolvedInTwoBatches() {
        List<Order> firstBatch = stuckOrders(BATCH);
        List<Order> secondBatch = stuckOrders(BATCH);
        when(orderRepository.findByStatusAndCreatedAtBefore(any(), any(), any()))
            .thenReturn(firstBatch, secondBatch);

        scheduler.reconcileStuckOrders();
        scheduler.reconcileStuckOrders();

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
            ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(orderRepository, times(2))
            .findByStatusAndCreatedAtBefore(any(), any(), pageCaptor.capture());
        pageCaptor.getAllValues().forEach(page ->
            assertThat(page.getPageSize()).isEqualTo(BATCH));

        verify(orderRepository, atLeast(TOTAL_STUCK)).save(any(Order.class));
    }

    @Test
    void batchSizeGreaterThanCandidatesSingleBatchOnly() {
        when(orderRepository.findByStatusAndCreatedAtBefore(any(), any(), any()))
            .thenReturn(stuckOrders(10));

        scheduler.reconcileStuckOrders();

        verify(orderRepository, times(1))
            .findByStatusAndCreatedAtBefore(any(), any(), any());
    }

    private List<Order> stuckOrders(int n) {
        List<Order> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Order.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .promotionReservationId(UUID.randomUUID())
                .build());
        }
        return out;
    }
}
