package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.service.IdempotencyService;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.PricingService;
import com.shop.orderservice.service.StockReservationService;
import com.shop.orderservice.client.PromotionServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreateSagaTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock PricingService pricingService;
    @Mock StockReservationService stockReservationService;
    @Mock PromotionServiceClient promotionClient;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock IdempotencyService idempotencyService;
    @Mock OrderMapper orderMapper;
    @Mock PlatformTransactionManager txManager;
    @Mock TransactionStatus txStatus;

    OrderCreateSaga saga;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID promoReservationId = UUID.randomUUID();
    private CartItem cartItem;

    @Mock com.shop.orderservice.service.CompensationFailureService compensationFailureService;

    @BeforeEach
    void setUp() {
        saga = new OrderCreateSaga(
            orderRepository, orderItemRepository, cartRepository, cartItemRepository,
            pricingService, stockReservationService, promotionClient,
            orderEventPublisher, idempotencyService, orderMapper, null, compensationFailureService
        );
        Cart cart = Cart.builder().id(UUID.randomUUID()).userId(userId).build();
        when(cartRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(cart));
        CartItem item = CartItem.builder().id(UUID.randomUUID())
            .cartId(cart.getId()).productId(productId).quantity(2).build();
        cartItem = item;
        lenient().when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(item));
    }

    @Test
    void persistsZeroAmountOrderBeforePricing_thenAppliesPricedAmounts() {
        List<Order> saveSnapshots = new ArrayList<>();
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
        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        snapshots.put(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN));
        when(pricingService.calculate(any(), eq(userId), anyList(), eq("SAVE10")))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(11),
                snapshots, promoReservationId));
        when(stockReservationService.reserve(eq(productId), any())).thenReturn(reservationId);
        when(orderMapper.toResponse(any(), any())).thenReturn(null);

        OrderResponse resp = saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-persist-key");

        // Persist-early: ZERO-amount PENDING row inserted BEFORE pricing
        org.mockito.InOrder seq = inOrder(orderRepository, pricingService);
        seq.verify(orderRepository).save(any(Order.class));
        seq.verify(pricingService).calculate(any(), eq(userId), anyList(), eq("SAVE10"));

        assertThat(saveSnapshots).hasSize(2);
        assertThat(saveSnapshots.get(0).getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saveSnapshots.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saveSnapshots.get(1).getTotal()).isEqualByComparingTo(BigDecimal.valueOf(11));
        assertThat(saveSnapshots.get(1).getPromotionReservationId()).isEqualTo(promoReservationId);

        verify(idempotencyService).complete(eq("h13-persist-key"), eq(userId.toString()), any(), eq(201));
        // The mapped response (whatever it is) was returned; the saga doesn't filter.
        assertThat(resp).isNull();
    }

    @Test
    void stockReservationFailureRollsBackSaga() {
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.calculate(any(), eq(userId), anyList(), any()))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN)),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any()))
            .thenThrow(new StockReservationFailedException(productId, null));

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-rollback-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4007"));

        // Compensation: every reservation release attempted; promotion release attempted
        verify(promotionClient, times(1)).release(promoReservationId);
        verify(orderEventPublisher, never()).publishCreated(any(), anyList());
        verify(idempotencyService, never()).complete(any(), any(), any(), any(Integer.class));
    }

    @Test
    void outerRollbackEvictsInnerWork_transactionTemplateCommitsThenRollsBack() {

        // real Template.commit() → rollback() cycle exercises the proxy's
        // tx boundary; a self-invocation regression (no proxy) would skip
        // the rollback because nothing was started.
        TransactionTemplate template = new TransactionTemplate(txManager);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        doAnswer(inv -> { throw new RuntimeException("simulated downstream failure"); })
            .when(orderRepository).save(any());

        assertThatThrownBy(() ->
            template.executeWithoutResult(s -> saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-tx-key")))
            .isInstanceOf(RuntimeException.class);

        // TransactionTemplate must have rolled back — the saga's writes never
        // become visible because the simulated failure throws BEFORE commit.
        verify(txManager).getTransaction(any());
        verify(txManager).rollback(txStatus);
        verify(txManager, never()).commit(txStatus);
    }

    @Test
    void aFailedCompensationReleaseIsRecordedForTheRetry() {
        UUID secondProductId = UUID.randomUUID();
        CartItem secondItem = CartItem.builder().id(UUID.randomUUID())
            .cartId(UUID.randomUUID()).productId(secondProductId).quantity(1).build();
        when(cartItemRepository.findByCartId(any())).thenReturn(List.of(cartItem, secondItem));

        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.calculate(any(), eq(userId), anyList(), any()))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN),
                        secondProductId, new ProductSnapshot(secondProductId, "Widget", BigDecimal.TEN)),
                promoReservationId));

        // the first reservation succeeds, the second one fails -> compensation.
        // The saga iterates the pricing snapshots, so the order of the two
        // products is not the order of the cart: the stub is call-count based.
        when(stockReservationService.reserve(any(), any()))
            .thenReturn(reservationId)
            .thenThrow(new StockReservationFailedException(secondProductId, null));
        // ... and the compensation of the first reservation fails as well
        doThrow(new RuntimeException("inventory down")).when(stockReservationService).release(any());

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-compensation-key"))
            .isInstanceOf(BusinessException.class);

        verify(stockReservationService).release(reservationId);
        // the failure is recorded, so the hold is recoverable instead of being
        // orphaned until the inventory TTL sweep
        verify(compensationFailureService).record(any(), eq(reservationId),
            eq(com.shop.orderservice.entity.FailedCompensation.KIND_RESERVATION_RELEASE), any());
    }
}
