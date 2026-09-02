package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.repository.*;
import com.shop.orderservice.service.*;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H9 — admin cancel of a CONFIRMED order must release the COMMITTED
 * reservation (restocking inventory) so the goods return to the available
 * pool. The pre-fix code released only on PENDING and silently skipped
 * CONFIRMED — leaving stock stranded after every admin cancel.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplCancelConfirmedTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock PricingService pricingService;
    @Mock PromotionServiceClient promotionClient;
    @Mock PaymentServiceClient paymentClient;
    @Mock StockReservationService stockReservationService;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock IdempotencyService idempotencyService;
    @Mock OrderStatusService orderStatusService;
    @Mock OrderMapper orderMapper;
    @Mock ObjectMapper objectMapper;
    @Mock OrderCommitCoordinator commitCoordinator;
    @Mock OrderConfirmMetrics confirmMetrics;

    @InjectMocks OrderServiceImpl service;

    @Test
    void adminCancelConfirmedOrderReleasesCommittedReservation() {
        UUID orderId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Order order = Order.builder()
            .id(orderId)
            .userId(UUID.randomUUID())
            .status(OrderStatus.CONFIRMED)
            .subtotal(BigDecimal.TEN)
            .total(BigDecimal.TEN)
            .confirmedAt(Instant.now())
            .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(
            OrderItem.builder().orderId(orderId).reservationId(reservationId).build()
        ));
        when(orderMapper.toResponse(any(Order.class), any())).thenReturn(null);

        service.cancelOrder(orderId, adminId, /*isAdmin*/ true);

        // H9 — admin cancel CONFIRMED must call releaseCommitted, NOT release.
        verify(stockReservationService, times(1)).releaseCommitted(reservationId);
        verify(stockReservationService, never()).release(any(UUID.class));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void userCancelNonPendingRejected() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = Order.builder()
            .id(orderId)
            .userId(userId)
            .status(OrderStatus.CONFIRMED)
            .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId, /*isAdmin*/ false))
            .isInstanceOf(BusinessException.class);

        verify(stockReservationService, never()).releaseCommitted(any(UUID.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void userCancelPendingUsesPlainRelease() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Order order = Order.builder()
            .id(orderId)
            .userId(userId)
            .status(OrderStatus.PENDING)
            .subtotal(BigDecimal.TEN)
            .total(BigDecimal.TEN)
            .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(
            OrderItem.builder().orderId(orderId).reservationId(reservationId).build()
        ));
        when(orderMapper.toResponse(any(Order.class), any())).thenReturn(null);

        service.cancelOrder(orderId, userId, /*isAdmin*/ false);

        verify(stockReservationService, times(1)).release(reservationId);
        verify(stockReservationService, never()).releaseCommitted(any(UUID.class));
    }
}
