package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.ReserveRequest;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            saveSnapshots.add(Order.builder()
                .userId(o.getUserId()).status(o.getStatus())
                .subtotal(o.getSubtotal()).taxAmount(o.getTaxAmount())
                .discountAmount(o.getDiscountAmount()).total(o.getTotal())
                .couponCode(o.getCouponCode()).promotionReservationId(o.getPromotionReservationId())
                .build());
            return o;
        });
        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        snapshots.put(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND"));
        when(pricingService.calculate(any(), eq(userId), anyList(), eq("SAVE10")))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(11), "VND",
                snapshots, promoReservationId));
        when(stockReservationService.reserve(eq(productId), any())).thenReturn(reservationId);
        when(orderMapper.toResponse(any(), any())).thenReturn(null);

        OrderResponse resp = saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-persist-key");

        org.mockito.InOrder seq = inOrder(orderRepository, pricingService);
        seq.verify(orderRepository).save(any(Order.class));
        seq.verify(pricingService).calculate(any(), eq(userId), anyList(), eq("SAVE10"));

        assertThat(saveSnapshots).hasSize(2);
        assertThat(saveSnapshots.get(0).getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saveSnapshots.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saveSnapshots.get(1).getTotal()).isEqualByComparingTo(BigDecimal.valueOf(11));
        assertThat(saveSnapshots.get(1).getPromotionReservationId()).isEqualTo(promoReservationId);

        verify(idempotencyService).complete(eq("h13-persist-key"), eq(userId.toString()), any(), eq(201));
        assertThat(resp).isNull();
    }

    @Test
    void stockReservationFailureRollsBackSaga() {
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.calculate(any(), eq(userId), anyList(), any()))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND",
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND")),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any()))
            .thenThrow(new StockReservationFailedException(productId, null));

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-rollback-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4007"));

        verify(promotionClient, times(1)).release(promoReservationId);
        verify(orderEventPublisher, never()).publishCreated(any(), anyList());
        verify(idempotencyService, never()).complete(any(), any(), any(), any(Integer.class));
    }

    @Test
    void outerRollbackEvictsInnerWork_transactionTemplateCommitsThenRollsBack() {

        TransactionTemplate template = new TransactionTemplate(txManager);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        doAnswer(inv -> { throw new RuntimeException("simulated downstream failure"); })
            .when(orderRepository).save(any());

        assertThatThrownBy(() ->
            template.executeWithoutResult(s -> saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-tx-key")))
            .isInstanceOf(RuntimeException.class);

        verify(txManager).getTransaction(any());
        verify(txManager).rollback(txStatus);
        verify(txManager, never()).commit(txStatus);
    }

    @Test
    void finalCommitFailureReleasesReservationsAndRemovesTheGhostOrder() {
        List<Order> saved = new ArrayList<>();
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            saved.add(o);
            if (saved.size() >= 2) {
                throw new RuntimeException("final checkout commit lost the race");
            }
            return o;
        });
        when(orderRepository.findById(any(UUID.class)))
            .thenAnswer(inv -> saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(0)));
        when(orderItemRepository.findByOrderId(any(UUID.class))).thenReturn(List.of());
        when(pricingService.calculate(any(), eq(userId), anyList(), any()))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND",
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND")),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any())).thenReturn(reservationId);

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "tx2-ghost-key"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("final checkout commit lost the race");

        verify(stockReservationService, times(1)).release(reservationId);
        verify(promotionClient, times(1)).release(promoReservationId);
        verify(orderRepository, times(1)).delete(any(Order.class));
        verify(orderEventPublisher, never()).publishCreated(any(), anyList());
        verify(idempotencyService, never()).complete(any(), any(), any(), any(Integer.class));
    }

    @Test
    void ghostOrderRemovalFailureDoesNotMaskTheOriginalFailure() {
        List<Order> saved = new ArrayList<>();
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            saved.add(o);
            if (saved.size() >= 2) {
                throw new RuntimeException("final checkout commit lost the race");
            }
            return o;
        });
        when(orderRepository.findById(any(UUID.class)))
            .thenAnswer(inv -> saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(0)));
        when(orderItemRepository.findByOrderId(any(UUID.class))).thenReturn(List.of());
        doThrow(new RuntimeException("ghost delete down"))
            .when(orderRepository).delete(any(Order.class));
        when(pricingService.calculate(any(), eq(userId), anyList(), any()))
            .thenReturn(new PricingBreakdown(
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND",
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND")),
                promoReservationId));
        when(stockReservationService.reserve(eq(productId), any())).thenReturn(reservationId);

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "tx2-masked-key"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("final checkout commit lost the race");

        verify(orderRepository, times(1)).delete(any(Order.class));
        verify(stockReservationService, times(1)).release(reservationId);
    }

    @Test
    void losingRacersReleaseOnlyTheirOwnReservationsAndDeleteOnlyTheirOwnGhostOrders() throws Exception {
        int racers = 20;
        Map<UUID, UUID> reservationByOrder = new ConcurrentHashMap<>();
        Map<UUID, Order> ordersById = new ConcurrentHashMap<>();
        Map<UUID, AtomicInteger> saveCounts = new ConcurrentHashMap<>();
        AtomicReference<UUID> winnerOrderId = new AtomicReference<>();
        AtomicBoolean winnerPassedPricing = new AtomicBoolean(false);
        CountDownLatch winnerCompleted = new CountDownLatch(1);
        CountDownLatch losersGate = new CountDownLatch(1);
        List<UUID> releasedIds = Collections.synchronizedList(new ArrayList<>());
        List<UUID> deletedOrderIds = Collections.synchronizedList(new ArrayList<>());

        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            ordersById.put(o.getId(), o);
            int saves = saveCounts.computeIfAbsent(o.getId(), k -> new AtomicInteger()).incrementAndGet();
            if (saves >= 2 && winnerCompleted.getCount() == 0
                    && !o.getId().equals(winnerOrderId.get())) {
                throw new RuntimeException("simulated lost cart race on the final commit");
            }
            return o;
        });
        when(orderRepository.findById(any(UUID.class)))
            .thenAnswer(inv -> Optional.ofNullable(ordersById.get(inv.getArgument(0))));
        when(orderItemRepository.findByOrderId(any(UUID.class))).thenReturn(List.of());
        doAnswer(inv -> {
            deletedOrderIds.add(((Order) inv.getArgument(0)).getId());
            return null;
        }).when(orderRepository).delete(any(Order.class));
        doAnswer(inv -> {
            releasedIds.add(inv.getArgument(0));
            return null;
        }).when(stockReservationService).release(any(UUID.class));
        doAnswer(inv -> {
            winnerCompleted.countDown();
            return null;
        }).when(orderEventPublisher).publishCreated(any(), anyList());
        when(stockReservationService.reserve(eq(productId), any())).thenAnswer(inv -> {
            ReserveRequest request = inv.getArgument(1);
            UUID reservationForRacer = UUID.randomUUID();
            reservationByOrder.put(request.orderId(), reservationForRacer);
            return reservationForRacer;
        });
        when(pricingService.calculate(any(), eq(userId), anyList(), any())).thenAnswer(inv -> {
            UUID orderId = inv.getArgument(0);
            if (winnerPassedPricing.compareAndSet(false, true)) {
                winnerOrderId.set(orderId);
                return pricedBreakdown();
            }
            if (!losersGate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("losers gate timeout");
            }
            return pricedBreakdown();
        });

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier barrier = new CyclicBarrier(racers);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "race-key-" + UUID.randomUUID());
                }));
            }
            org.assertj.core.api.Assertions.assertThat(winnerCompleted.await(60, TimeUnit.SECONDS)).isTrue();
            UUID winner = winnerOrderId.get();
            losersGate.countDown();

            int succeeded = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<OrderResponse> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }

            org.assertj.core.api.Assertions.assertThat(succeeded).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(failures).hasSize(racers - 1);
            org.assertj.core.api.Assertions.assertThat(failures)
                .allSatisfy(ex -> org.assertj.core.api.Assertions.assertThat(ex).isInstanceOf(BusinessException.class));

            List<UUID> loserReservationIds = reservationByOrder.entrySet().stream()
                .filter(e -> !e.getKey().equals(winner))
                .map(Map.Entry::getValue)
                .toList();
            org.assertj.core.api.Assertions.assertThat(loserReservationIds).hasSize(racers - 1);
            org.assertj.core.api.Assertions.assertThat(releasedIds)
                .containsExactlyInAnyOrderElementsOf(loserReservationIds);
            org.assertj.core.api.Assertions.assertThat(releasedIds).doesNotContain(reservationByOrder.get(winner));
            org.assertj.core.api.Assertions.assertThat(deletedOrderIds)
                .hasSize(racers - 1)
                .doesNotContain(winner)
                .doesNotHaveDuplicates();
            verify(stockReservationService, never()).release(reservationByOrder.get(winner));
        } finally {
            losersGate.countDown();
            pool.shutdownNow();
        }
    }

    private PricingBreakdown pricedBreakdown() {
        return new PricingBreakdown(
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND",
            Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND")),
            promoReservationId);
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
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND",
                Map.of(productId, new ProductSnapshot(productId, "Widget", BigDecimal.TEN, "VND"),
                        secondProductId, new ProductSnapshot(secondProductId, "Widget", BigDecimal.TEN, "VND")),
                promoReservationId));

        when(stockReservationService.reserve(any(), any()))
            .thenReturn(reservationId)
            .thenThrow(new StockReservationFailedException(secondProductId, null));
        doThrow(new RuntimeException("inventory down")).when(stockReservationService).release(any());

        assertThatThrownBy(() ->
            saga.execute(userId, new OrderCreateRequest(null, "SAVE10"), "h13-compensation-key"))
            .isInstanceOf(BusinessException.class);

        verify(stockReservationService).release(reservationId);
        verify(compensationFailureService).record(any(), eq(reservationId),
            eq(com.shop.orderservice.entity.FailedCompensation.KIND_RESERVATION_RELEASE), any());
    }
}
