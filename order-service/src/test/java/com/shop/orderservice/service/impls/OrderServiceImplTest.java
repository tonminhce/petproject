package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.*;
import com.shop.orderservice.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock com.shop.orderservice.client.PromotionServiceClient promotionClient;
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
        // orderStatusService is a mock — encode the real state-machine contract
        // (DELIVERED is terminal) instead of relying on service internals.
        doThrow(BusinessException.of(ErrorCode.ORDER_INVALID_STATE_TRANSITION,
                OrderStatus.DELIVERED, OrderStatus.CANCELLED))
            .when(orderStatusService).validateTransition(OrderStatus.DELIVERED, OrderStatus.CANCELLED);

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
        verify(pricingService, never()).calculate(any(), any(), any(), any());  // saga NOT re-run
    }

    @Test
    void confirmOrder_setsConfirmedAt() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.confirmOrder(orderId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
    }

    @Test
    void shipOrder_setsShippedAt() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.shipOrder(orderId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isNotNull();
    }

    @Test
    void deliverOrder_setsDeliveredAt() {
        order.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.deliverOrder(orderId);

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

    @Test
    void cancelOrder_pendingReleasesPromotionReservation_once() {
        UUID promoReservationId = UUID.randomUUID();
        order.setPromotionReservationId(promoReservationId);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        var item = new com.shop.orderservice.entity.OrderItem();
        item.setReservationId(UUID.randomUUID());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));

        service.cancelOrder(orderId, userId, false);

        verify(promotionClient, times(1)).release(promoReservationId);
        // promotion release happens AFTER stock release (mirrors saga compensation order)
        org.mockito.InOrder seq = inOrder(stockReservationService, promotionClient);
        seq.verify(stockReservationService).release(item.getReservationId());
        seq.verify(promotionClient).release(promoReservationId);
    }

    @Test
    void cancelOrder_adminCancelConfirmed_doesNotReleasePromotionReservation() {
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPromotionReservationId(UUID.randomUUID());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        service.cancelOrder(orderId, userId, true);

        // reservations already COMMITTED at confirm — release belongs to Phase 8 refund
        verify(promotionClient, never()).release(any());
    }

    @Test
    void cancelOrder_nullPromotionReservationId_doesNotCallRelease() {
        assertThat(order.getPromotionReservationId()).isNull();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        var item = new com.shop.orderservice.entity.OrderItem();
        item.setReservationId(UUID.randomUUID());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));

        service.cancelOrder(orderId, userId, false);

        verify(promotionClient, never()).release(any());
    }

    // ========================================================================
    // CREATE — persist-early saga (Task 7 re-attempt ruling)
    // ========================================================================

    private void stubHappyCart() {
        var cart = com.shop.orderservice.entity.Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
            com.shop.orderservice.entity.CartItem.builder()
                .cartId(cart.getId()).productId(productId).productTitle("Test Product")
                .unitPrice(BigDecimal.TEN).quantity(1).build()));
    }

    private void stubIdempotency() throws Exception {
        when(objectMapper.writeValueAsBytes(any())).thenReturn("{}".getBytes());
        when(idempotencyService.begin(any(), eq(userId), any())).thenReturn(Optional.empty());
    }

    @Test
    void createOrder_persistsZeroAmountOrderBeforePricing_thenAppliesPricedAmounts() throws Exception {
        stubHappyCart();
        stubIdempotency();
        UUID reservationId = UUID.randomUUID();
        // Both saves receive the SAME managed instance — snapshot inside save() to
        // observe the entity state AS IT WAS at each call, not after later mutation.
        java.util.List<Order> saveSnapshots = new java.util.ArrayList<>();
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            saveSnapshots.add(Order.builder()
                .userId(o.getUserId()).status(o.getStatus())
                .subtotal(o.getSubtotal()).taxAmount(o.getTaxAmount())
                .discountAmount(o.getDiscountAmount()).total(o.getTotal())
                .couponCode(o.getCouponCode()).promotionReservationId(o.getPromotionReservationId())
                .build());
            return o;
        });
        when(pricingService.calculate(any(), eq(userId), anyList(), eq("SAVE10")))
            .thenReturn(new com.shop.orderservice.dto.internal.PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(11),
                java.util.Map.of(productId, new com.shop.orderservice.dto.internal.ProductSnapshot(
                    productId, "Test Product", BigDecimal.TEN)),
                reservationId));
        when(stockReservationService.reserve(eq(productId), any())).thenReturn(UUID.randomUUID());
        when(orderMapper.toResponse(any(), any())).thenReturn(null);

        service.createOrder(userId, new OrderCreateRequest(null, "SAVE10"), "persist-early-key");

        // Persist-early contract: the ZERO-amount PENDING row is inserted BEFORE pricing runs
        org.mockito.InOrder seq = inOrder(orderRepository, pricingService);
        seq.verify(orderRepository).save(any(Order.class));
        seq.verify(pricingService).calculate(any(), eq(userId), anyList(), eq("SAVE10"));

        assertThat(saveSnapshots).hasSize(2);
        Order initial = saveSnapshots.get(0);
        assertThat(initial.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(initial.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(initial.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(initial.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(initial.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(initial.getCouponCode()).isEqualTo("SAVE10");

        // Second save (same TX) carries priced amounts + the promotion reservation id
        Order priced = saveSnapshots.get(1);
        assertThat(priced.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(11));
        assertThat(priced.getPromotionReservationId()).isEqualTo(reservationId);
    }

    @Test
    void createOrder_stockReserveFails_releasesPromotionReservationThenThrows() throws Exception {
        stubHappyCart();
        stubIdempotency();
        UUID promoReservationId = UUID.randomUUID();
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.calculate(any(), eq(userId), anyList(), eq("SAVE10")))
            .thenReturn(new com.shop.orderservice.dto.internal.PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                java.util.Map.of(productId, new com.shop.orderservice.dto.internal.ProductSnapshot(
                    productId, "Test Product", BigDecimal.TEN)),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any()))
            .thenThrow(new StockReservationFailedException(productId, null));

        assertThatThrownBy(() -> service.createOrder(userId, new OrderCreateRequest(null, "SAVE10"), "promo-comp-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4007"));

        verify(promotionClient).release(promoReservationId);
    }

    @Test
    void createOrder_stockReserveFails_swallowsPromotionReleaseFailure() throws Exception {
        stubHappyCart();
        stubIdempotency();
        UUID promoReservationId = UUID.randomUUID();
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.calculate(any(), eq(userId), anyList(), eq("SAVE10")))
            .thenReturn(new com.shop.orderservice.dto.internal.PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                java.util.Map.of(productId, new com.shop.orderservice.dto.internal.ProductSnapshot(
                    productId, "Test Product", BigDecimal.TEN)),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any()))
            .thenThrow(new StockReservationFailedException(productId, null));
        doThrow(new RuntimeException("promotion down")).when(promotionClient).release(promoReservationId);

        assertThatThrownBy(() -> service.createOrder(userId, new OrderCreateRequest(null, "SAVE10"), "promo-down-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4007"));  // swallow — original error preserved
    }
}
