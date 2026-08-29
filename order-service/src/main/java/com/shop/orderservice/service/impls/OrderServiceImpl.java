package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.service.IdempotencyService;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.OrderService;
import com.shop.orderservice.service.OrderStatusService;
import com.shop.orderservice.service.PricingService;
import com.shop.orderservice.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final PricingService pricingService;
    private final StockReservationService stockReservationService;
    private final OrderEventPublisher orderEventPublisher;
    private final IdempotencyService idempotencyService;
    private final OrderStatusService orderStatusService;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // CREATE ORDER — SAGA with explicit compensation
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Idempotency pre-insert (REQUIRES_NEW — commits in-flight row before saga)
        Optional<OrderResponse> cached = idempotencyService.begin(idempotencyKey, userId, hash(request));
        if (cached.isPresent()) return cached.get();  // ← rev 2 fix O-N1 (REPLAY — DO NOT re-run saga)

        // 2. Delegate to doCreateOrder — all-or-nothing failure wrapped in single catch
        try {
            return doCreateOrder(userId, request, idempotencyKey);
        } catch (RuntimeException ex) {
            // rev 2 fix O-N2: single catch covers validation, pricing, reserve, etc.
            idempotencyService.abort(idempotencyKey, userId);
            throw ex;
        }
    }

    /**
     * Saga body — NOT annotated {@code @Transactional}. Runs in the TX opened by
     * {@link #createOrder(UUID, OrderCreateRequest, String)} (proxy-invoked). Self-invocation
     * would bypass the proxy — do not call this method from inside the same class.
     */
    private OrderResponse doCreateOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Load cart + validate
        Cart cart = (request.cartId() != null)
            ? cartRepository.findByIdAndUserIdAndDeletedFalse(request.cartId(), userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, request.cartId()))
            : cartRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_EMPTY));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) throw BusinessException.of(ErrorCode.CART_EMPTY);

        // 2. Pricing (remote: product + tax + promotion)
        PricingBreakdown pricing = pricingService.calculate(userId, items, request.couponCode());

        // 3. Create Order + OrderItems FIRST (so we have orderId for ReserveRequest)
        Order order = Order.builder()
            .userId(userId).status(OrderStatus.PENDING)
            .subtotal(pricing.subtotal()).taxAmount(pricing.taxAmount())
            .discountAmount(pricing.discountAmount()).total(pricing.total())
            .couponCode(request.couponCode())
            .build();
        order = orderRepository.save(order);
        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem item : items) {
            ProductSnapshot snapshot = pricing.snapshots().get(item.getProductId());
            OrderItem orderItem = OrderItem.builder()
                .orderId(order.getId())
                .productId(item.getProductId())
                .productTitle(snapshot.title())
                .quantity(item.getQuantity())
                .unitPrice(snapshot.unitPrice())
                .lineTotal(snapshot.unitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())))
                .build();
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);

        // 4. Reserve stock per item — reservationId stored on OrderItem for cancel/compensation
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (OrderItem orderItem : orderItems) {
                UUID reservationId = stockReservationService.reserve(
                    orderItem.getProductId(),
                    new ReserveRequest(orderItem.getQuantity(), order.getId()));
                orderItem.setReservationId(reservationId);
                reserved.add(orderItem);
            }
            orderItemRepository.saveAll(orderItems);  // persist reservationIds
        } catch (StockReservationFailedException ex) {
            // Compensation: release all reservations
            releaseAllReservations(reserved);
            throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getProductId());
        }

        // 5. Clear cart
        cartItemRepository.deleteAll(items);
        cart.markDeleted("system");
        cartRepository.save(cart);

        // 6. Publish OrderCreated event (same TX — atomic with order insert)
        orderEventPublisher.publishCreated(order, orderItems);

        // 7. Build response + complete idempotency (same TX)
        OrderResponse response = orderMapper.toResponse(order, orderItems);
        idempotencyService.complete(idempotencyKey, userId, response, 201);
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
                // DO NOT throw — would mask original error
            }
        }
    }

    /**
     * SHA-256 hex of JSON-serialized request body (Jackson deterministic for record field order).
     * 64 hex chars fits {@code idempotency_keys.request_hash VARCHAR(64)}.
     */
    private String hash(OrderCreateRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash request body for idempotency", ex);
        }
    }

    // ========================================================================
    // CANCEL
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        // Authorization: hide existence from non-owners
        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
        }

        // Policy: USER chỉ cancel PENDING (chưa charge); ADMIN cancel PENDING + CONFIRMED.
        // SHIPPED/DELIVERED/CANCELLED: không ai cancel được.
        if (!isAdmin && order.getStatus() != OrderStatus.PENDING) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }
        orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }

        // Release stock CHỈ khi PENDING (reservations còn PENDING trong inventory).
        // CONFIRMED: reservations đã COMMITTED — release endpoint sẽ throw RESERVATION_INVALID_STATE.
        // Restock cho CONFIRMED: refund flow (Phase 8) hoặc admin adjust thủ công.
        if (order.getStatus() == OrderStatus.PENDING) {
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(java.util.Objects::nonNull)
                .toList();
            releaseAllReservationsById(reservationIds);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        // NO markDeleted — cancelled orders must remain in user/admin history (rev 2 fix)
        orderRepository.save(order);

        orderEventPublisher.publishCancelled(order);
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    private void releaseAllReservationsById(List<UUID> reservationIds) {
        for (UUID id : reservationIds) {
            try {
                stockReservationService.release(id);
            } catch (Exception ex) {
                log.error("Failed to release reservation {} during cancel", id, ex);
            }
        }
    }

    // ========================================================================
    // STATUS TRANSITIONS (admin / service-to-service in Phase 8)
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse confirmOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.CONFIRMED, isAdmin, () -> Instant.now());
    }

    @Override
    @Transactional
    public OrderResponse shipOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.SHIPPED, isAdmin, () -> Instant.now());
    }

    @Override
    @Transactional
    public OrderResponse deliverOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.DELIVERED, isAdmin, () -> Instant.now());
    }

    private OrderResponse transitionStatus(UUID orderId, OrderStatus to, boolean isAdmin,
                                          java.util.function.Supplier<Instant> timestampSupplier) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
        orderStatusService.validateTransition(order.getStatus(), to);
        Instant now = timestampSupplier.get();
        switch (to) {
            case CONFIRMED -> order.setConfirmedAt(now);
            case SHIPPED -> order.setShippedAt(now);
            case DELIVERED -> order.setDeliveredAt(now);
            case CANCELLED -> order.setCancelledAt(now);  // not used here — cancelOrder uses different flow
            default -> { /* unreachable */ }
        }
        order.setStatus(to);
        orderRepository.save(order);
        orderEventPublisher.publishStatusChanged(order);
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);  // hide existence
        }
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(order -> orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findAll(OrderStatus status, Pageable pageable) {
        Page<Order> page = (status != null)
            ? orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            : orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(order -> orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId())));
    }
}