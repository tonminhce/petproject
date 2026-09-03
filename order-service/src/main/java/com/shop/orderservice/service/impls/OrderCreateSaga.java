package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.PromotionServiceClient;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Saga orchestrator for {@code OrderServiceImpl.createOrder}.
 *
 * <p>Separates DB transaction boundaries from remote HTTP calls (Pricing,
 * Stock Reservation) to prevent HikariCP connection pool exhaustion during
 * downstream latency spikes.</p>
 */
@Service
@Slf4j
public class OrderCreateSaga {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final PricingService pricingService;
    private final StockReservationService stockReservationService;
    private final PromotionServiceClient promotionClient;
    private final OrderEventPublisher orderEventPublisher;
    private final IdempotencyService idempotencyService;
    private final OrderMapper orderMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public OrderCreateSaga(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            PricingService pricingService,
            StockReservationService stockReservationService,
            PromotionServiceClient promotionClient,
            OrderEventPublisher orderEventPublisher,
            IdempotencyService idempotencyService,
            OrderMapper orderMapper,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.pricingService = pricingService;
        this.stockReservationService = stockReservationService;
        this.promotionClient = promotionClient;
        this.orderEventPublisher = orderEventPublisher;
        this.idempotencyService = idempotencyService;
        this.orderMapper = orderMapper;
        this.transactionTemplate = (transactionManager != null) ? new TransactionTemplate(transactionManager) : null;
    }

    public OrderCreateSaga(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            PricingService pricingService,
            StockReservationService stockReservationService,
            PromotionServiceClient promotionClient,
            OrderEventPublisher orderEventPublisher,
            IdempotencyService idempotencyService,
            OrderMapper orderMapper) {
        this(orderRepository, orderItemRepository, cartRepository, cartItemRepository,
             pricingService, stockReservationService, promotionClient,
             orderEventPublisher, idempotencyService, orderMapper, null);
    }

    private <T> T executeInTx(Supplier<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private void executeInTxWithoutResult(Runnable action) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private record CartAndOrder(Cart cart, List<CartItem> items, Order order) {
    }

    /**
     * Executes the order creation saga with isolated transaction boundaries.
     */
    public OrderResponse execute(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // Step 1 (DB TX): Load cart + persist-early PENDING order
        CartAndOrder initial = executeInTx(() -> {
            Cart cart = (request.cartId() != null)
                ? cartRepository.findByIdAndUserIdAndDeletedFalse(request.cartId(), userId)
                    .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, request.cartId()))
                : cartRepository.findByUserIdAndDeletedFalse(userId)
                    .orElseThrow(() -> BusinessException.of(ErrorCode.CART_EMPTY));
            List<CartItem> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .sorted(Comparator.comparing(CartItem::getProductId))
                .toList();
            if (items.isEmpty()) {
                throw BusinessException.of(ErrorCode.CART_EMPTY);
            }

            Order order = orderRepository.save(Order.builder()
                .userId(userId).status(OrderStatus.PENDING)
                .subtotal(BigDecimal.ZERO).taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO).total(BigDecimal.ZERO)
                .couponCode(request.couponCode())
                .recipientName(request.recipientName())
                .phoneNumber(request.phoneNumber())
                .shippingAddress(request.shippingAddress())
                .build());

            return new CartAndOrder(cart, items, order);
        });

        Cart cart = initial.cart();
        List<CartItem> items = initial.items();
        Order order = initial.order();

        // Step 2 (OUTSIDE DB TX - HikariCP released): Pricing calculation
        PricingBreakdown pricing;
        try {
            pricing = pricingService.calculate(order.getId(), userId, items, request.couponCode());
        } catch (Exception ex) {
            executeInTxWithoutResult(() -> orderRepository.delete(order));
            throw ex;
        }

        order.setSubtotal(pricing.subtotal());
        order.setTaxAmount(pricing.taxAmount());
        order.setDiscountAmount(pricing.discountAmount());
        order.setTotal(pricing.total());
        order.setPromotionReservationId(pricing.promotionReservationId());

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem item : items) {
            ProductSnapshot snapshot = pricing.snapshots().get(item.getProductId());
            OrderItem orderItem = OrderItem.builder()
                .orderId(order.getId())
                .productId(item.getProductId())
                .productTitle(snapshot.title())
                .quantity(item.getQuantity())
                .unitPrice(snapshot.unitPrice())
                .lineTotal(snapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .build();
            orderItems.add(orderItem);
        }

        // Step 3 (OUTSIDE DB TX - HikariCP released): Reserve stock per item
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (OrderItem orderItem : orderItems) {
                UUID reservationId = stockReservationService.reserve(
                    orderItem.getProductId(),
                    new ReserveRequest(orderItem.getQuantity(), order.getId()));
                orderItem.setReservationId(reservationId);
                reserved.add(orderItem);
            }
        } catch (Exception ex) {
            releaseAllReservations(reserved);
            if (order.getPromotionReservationId() != null) {
                try {
                    promotionClient.release(order.getPromotionReservationId());
                } catch (Exception pex) {
                    log.error("Failed to release promotion reservation {} — TTL sweep covers",
                        order.getPromotionReservationId(), pex);
                }
            }
            executeInTxWithoutResult(() -> orderRepository.delete(order));
            if (ex instanceof StockReservationFailedException sfe) {
                throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, sfe.getProductId());
            }
            if (ex instanceof BusinessException be) {
                throw be;
            }
            throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getMessage());
        }

        // Step 4 (DB TX): Save finalized order, items, clear cart, publish outbox
        executeInTxWithoutResult(() -> {
            orderRepository.save(order);
            orderItemRepository.saveAll(orderItems);
            cartItemRepository.deleteAll(items);
            cart.markDeleted(userId.toString());
            cartRepository.save(cart);
            orderEventPublisher.publishCreated(order, orderItems);
        });

        // Step 5: Complete idempotency and map response
        OrderResponse response = orderMapper.toResponse(order, orderItems);
        idempotencyService.complete(idempotencyKey, userId.toString(), response, 201);
        return response;
    }

    private void releaseAllReservations(List<OrderItem> reserved) {
        for (OrderItem item : reserved) {
            try {
                if (item.getReservationId() != null) {
                    stockReservationService.release(item.getReservationId());
                }
            } catch (Exception ex) {
                log.error("Failed to release reservation {} for product {} during compensation",
                    item.getReservationId(), item.getProductId(), ex);
            }
        }
    }
}
