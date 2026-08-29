package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.*;
import com.shop.orderservice.service.*;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock PricingService pricingService;
    @Mock StockReservationService stockReservationService;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock IdempotencyService idempotencyService;
    @Mock OrderStatusService orderStatusService;
    @Mock OrderMapper orderMapper;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks OrderServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private Order order;

    @BeforeEach
    void setUp() {
        order = Order.builder().id(orderId).userId(userId).status(OrderStatus.PENDING)
            .subtotal(BigDecimal.valueOf(100)).total(BigDecimal.valueOf(110)).build();
    }

    @Test
    void cancelOrder_userCanCancelPending() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.cancelOrder(orderId, userId, false);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelOrder_userCannotCancelConfirmed() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4003"));
    }

    @Test
    void cancelOrder_adminCanCancelConfirmed() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        service.cancelOrder(orderId, userId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_throwsOnDelivered() {
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId, true))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelOrder_hidesExistenceForNonOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, otherUser, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4001"));  // NOT_FOUND, not FORBIDDEN
    }

    @Test
    void createOrder_returnsCachedResponseOnReplay() throws Exception {
        OrderCreateRequest req = new OrderCreateRequest(null, null);
        OrderResponse cached = new OrderResponse(orderId, userId, OrderStatus.PENDING, List.of(),
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null,
            Instant.now(), null, null, null, null);

        // hash() runs BEFORE idempotencyService.begin() — stub ObjectMapper to return non-null bytes
        when(objectMapper.writeValueAsBytes(any())).thenReturn("{}".getBytes());
        when(idempotencyService.begin(eq("key1"), eq(userId), any()))
            .thenReturn(Optional.of(cached));

        OrderResponse result = service.createOrder(userId, req, "key1");

        assertThat(result).isEqualTo(cached);
        verify(pricingService, never()).calculate(any(), any(), any());  // saga NOT re-run
    }

    @Test
    void confirmOrder_setsConfirmedAt() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.confirmOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
    }

    @Test
    void shipOrder_setsShippedAt() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.shipOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isNotNull();
    }

    @Test
    void deliverOrder_setsDeliveredAt() {
        order.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.deliverOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isNotNull();
    }

    @Test
    void findById_hidesForNonOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.findById(orderId, otherUser, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4001"));
    }

    @Test
    void findById_returnsForAdminRegardlessOfOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(orderMapper.toResponse(eq(order), any())).thenReturn(null);

        assertThatCode(() -> service.findById(orderId, otherUser, true)).doesNotThrowAnyException();
    }

    @Test
    void cancelOrder_pendingReleasesStock() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        var item = new com.shop.orderservice.entity.OrderItem();
        item.setReservationId(UUID.randomUUID());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));

        service.cancelOrder(orderId, userId, false);

        verify(stockReservationService).release(item.getReservationId());
    }
}
