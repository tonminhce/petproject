package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.constant.OrderStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * H13 — saga body for {@code OrderServiceImpl.createOrder} lives in a sibling
 * bean so the {@code @Transactional} boundary is honoured on every entry.
 *
 * <p>The pre-fix code defined {@code doCreateOrder} as a {@code private}
 * method on {@code OrderServiceImpl} and called it from
 * {@code OrderServiceImpl.createOrder}. Self-invocation on the same Spring
 * bean bypasses the proxy, so the {@code @Transactional} on the outer method
 * was the only thing keeping the saga in one transaction — and a refactor
 * that introduced a second {@code @Transactional} on {@code doCreateOrder}
 * would silently NOT take effect, leaving the boundary in the wrong place.</p>
 *
 * <p>This sibling bean re-states the {@code @Transactional} boundary
 * explicitly (so a regression that drops it on the caller still has the
 * proxy wrap the body) and is invoked through Spring, so the proxy is on
 * the path.</p>
 */
@Service
@RequiredArgsConstructor
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

    /**
     * The full saga body — explicitly {@code @Transactional} so the proxy
     * is on the path and any rollback (e.g. {@link StockReservationFailedException})
     * evicts everything done before the throw.
     */
    @Transactional
    public OrderResponse execute(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Load cart + validate
        Cart cart = (request.cartId() != null)
            ? cartRepository.findByIdAndUserIdAndDeletedFalse(request.cartId(), userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, request.cartId()))
            : cartRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_EMPTY));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId()).stream()
            .sorted(Comparator.comparing(CartItem::getProductId))
            .toList();
        if (items.isEmpty()) throw BusinessException.of(ErrorCode.CART_EMPTY);

        // 2. Persist-early
        Order order = orderRepository.save(Order.builder()
            .userId(userId).status(OrderStatus.PENDING)
            .subtotal(BigDecimal.ZERO).taxAmount(BigDecimal.ZERO)
            .discountAmount(BigDecimal.ZERO).total(BigDecimal.ZERO)
            .couponCode(request.couponCode())
            .build());

        // 3. Pricing (remote: product + tax + promotion)
        PricingBreakdown pricing = pricingService.calculate(order.getId(), userId, items, request.couponCode());

        // 4. Update amounts
        order.setSubtotal(pricing.subtotal());
        order.setTaxAmount(pricing.taxAmount());
        order.setDiscountAmount(pricing.discountAmount());
        order.setTotal(pricing.total());
        order.setPromotionReservationId(pricing.promotionReservationId());
        orderRepository.save(order);

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
        orderItemRepository.saveAll(orderItems);

        // 5. Reserve stock per item
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (OrderItem orderItem : orderItems) {
                UUID reservationId = stockReservationService.reserve(
                    orderItem.getProductId(),
                    new ReserveRequest(orderItem.getQuantity(), order.getId()));
                orderItem.setReservationId(reservationId);
                reserved.add(orderItem);
            }
            orderItemRepository.saveAll(orderItems);
        } catch (StockReservationFailedException ex) {
            releaseAllReservations(reserved);
            if (order.getPromotionReservationId() != null) {
                try {
                    promotionClient.release(order.getPromotionReservationId());
                } catch (Exception pex) {
                    log.error("Failed to release promotion reservation {} — TTL sweep covers",
                        order.getPromotionReservationId(), pex);
                }
            }
            throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getProductId());
        }

        // 6. Clear cart
        cartItemRepository.deleteAll(items);
        cart.markDeleted(userId.toString());
        cartRepository.save(cart);

        // 7. Publish event
        orderEventPublisher.publishCreated(order, orderItems);

        // 8. Response + complete idempotency
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
