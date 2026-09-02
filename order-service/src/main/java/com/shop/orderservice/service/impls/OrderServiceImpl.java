package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.internal.PaymentStatusSnapshot;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderItemResponse;
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
import com.shop.orderservice.service.OrderCommitCoordinator;
import com.shop.orderservice.service.OrderConfirmMetrics;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final PricingService pricingService;
    private final PromotionServiceClient promotionClient;
    private final PaymentServiceClient paymentClient;
    private final StockReservationService stockReservationService;
    private final OrderEventPublisher orderEventPublisher;
    private final IdempotencyService idempotencyService;
    private final OrderStatusService orderStatusService;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final OrderCommitCoordinator commitCoordinator;
    private final OrderConfirmMetrics confirmMetrics;
    private final OrderCreateSaga orderCreateSaga;

    // ========================================================================
    // CREATE ORDER — SAGA with explicit compensation
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Idempotency pre-insert (in-flight row commits in its own TX, before the saga).
        // Actor label = the customer's sub (UUID canonical text) — H-6 rows are string-keyed.
        String requestHash = hash(request);
        Optional<OrderResponse> cached = idempotencyService.begin(idempotencyKey, userId.toString(), requestHash);
        if (cached.isPresent()) return cached.get();  // REPLAY — do not re-run the saga

        // 2. H13 — delegate to OrderCreateSaga (sibling bean) so the @Transactional
        // boundary on the saga body is honoured through the Spring proxy. Self-invocation
        // on this class would bypass the proxy, leaving the saga outside any tx.
        try {
            return orderCreateSaga.execute(userId, request, idempotencyKey);
        } catch (RuntimeException ex) {
            // Single catch covers validation, pricing, reserve, etc. begin() throws are
            // deliberately OUTSIDE this try: a collision 409 must never delete another
            // execution's in-flight row; the requestHash guard in abort() is the second lock.
            idempotencyService.abort(idempotencyKey, userId.toString(), requestHash);
            throw ex;
        }
    }

    /**
     * SHA-256 hex (64 chars — fits {@code idempotency_keys.request_hash VARCHAR(64)}).
     * Shared by create (JSON-serialized body) and confirm (orderId string).
     */
    private String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash request body for idempotency", ex);
        }
    }

    /** SHA-256 hex of JSON-serialized request body (Jackson deterministic for record field order). */
    private String hash(OrderCreateRequest request) {
        try {
            return sha256Hex(new String(objectMapper.writeValueAsBytes(request), StandardCharsets.UTF_8));
        } catch (JsonProcessingException ex) {
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

        // Policy: USER may cancel PENDING only (not yet confirmed); ADMIN may cancel
        // PENDING + CONFIRMED. SHIPPED/DELIVERED/CANCELLED are terminal for everyone —
        // validateTransition is the single source of truth for both roles.
        if (!isAdmin && order.getStatus() != OrderStatus.PENDING) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }
        orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);

        // Release stock only when PENDING (reservations are still PENDING in inventory).
        // CONFIRMED: reservations are already COMMITTED — H9 fix releases them via the
        // releaseCommitted endpoint (restocking) on admin cancel. Without it, admin
        // cancellation leaves stock stranded after the order is marked CANCELLED.
        if (order.getStatus() == OrderStatus.PENDING) {
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(Objects::nonNull)
                .toList();
            releaseAllReservationsById(reservationIds);
            // Best-effort promotion release (spec §12 swallow pattern — TTL sweep covers
            // failures; never mask the cancel flow).
            if (order.getPromotionReservationId() != null) {
                try {
                    promotionClient.release(order.getPromotionReservationId());
                } catch (Exception pex) {
                    log.error("Failed to release promotion reservation {} during cancel",
                        order.getPromotionReservationId(), pex);
                }
            }
        } else if (order.getStatus() == OrderStatus.CONFIRMED && isAdmin) {
            // H9 — admin cancel of CONFIRMED must restock (releaseCommitted). The
            // release() path would reject the row because it's COMMITTED, not
            // PENDING. Best-effort per the same convention as the PENDING branch.
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(Objects::nonNull)
                .toList();
            releaseAllCommittedReservationsById(reservationIds);
            if (order.getPromotionReservationId() != null) {
                try {
                    promotionClient.releaseCommitted(order.getPromotionReservationId());
                } catch (Exception pex) {
                    log.error("Failed to release committed promotion reservation {} during admin cancel",
                        order.getPromotionReservationId(), pex);
                }
            }
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

    /** H9 — admin cancel CONFIRMED path; restocks via releaseCommitted. */
    private void releaseAllCommittedReservationsById(List<UUID> reservationIds) {
        for (UUID id : reservationIds) {
            try {
                stockReservationService.releaseCommitted(id);
            } catch (Exception ex) {
                log.error("Failed to release committed reservation {} during admin cancel", id, ex);
            }
        }
    }

    // ========================================================================
    // STATUS TRANSITIONS (admin / service-to-service in Phase 8)
    // ========================================================================

    // Endpoint-level authz lives on OrderStatusController (SERVICE or ADMIN); the
    // service layer only enforces the state machine. {@code actor} is the caller
    // label resolved by token shape at the controller (H-6: ADMIN → sub,
    // SERVICE → service:<azp>) — stored verbatim on idempotency rows.
    @Override
    @Transactional
    public OrderResponse confirmOrder(UUID orderId, String actor, String idempotencyKey) {
        confirmMetrics.attempt();
        String requestHash = sha256Hex(orderId.toString());
        Optional<OrderResponse> cached = (idempotencyKey == null)
            ? Optional.empty()
            : idempotencyService.begin(idempotencyKey, actor, requestHash);
        if (cached.isPresent()) return cached.get();  // REPLAY — do not re-run the commit

        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
            orderStatusService.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);
            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);

            // order-wiring D1 — payment gate BEFORE any remote commit: with the
            // payment-service flag on, confirm requires a CAPTURED payment. ANY
            // client failure also yields ORD-4012 (fail-closed — a payment we
            // could not verify must block confirm, and the raw exception never
            // leaks past this seam). Flag off (default) → guard bypassed.
            if (paymentClient.isEnabled()) {
                PaymentStatusSnapshot payment;
                try {
                    payment = paymentClient.findCapturedByOrderId(orderId).orElse(null);
                } catch (RuntimeException ex) {
                    log.error("Payment captured-check failed for order {} — failing closed", orderId, ex);
                    payment = null;
                }
                if (payment == null || !"CAPTURED".equals(payment.status())) {
                    throw BusinessException.of(ErrorCode.ORDER_PAYMENT_NOT_CAPTURED, orderId);
                }
            }

            // External commit BEFORE local state flip — local row stays PENDING until
            // inventory/promotion commits succeed (coordinator compensates on failure).
            // Phase timing (commit_inventory/commit_promotion) is owned by the
            // coordinator — no caller-side timer (would double-count §8 latencies).
            // ANY coordinator throw — including the clients' BusinessException mappings
            // for remote 4xx/5xx — surfaces as 409 ORD-4011 (task 13 IT: a remote 500
            // must not leak as 503). validateTransition's guard sits BEFORE this inner
            // try and is rethrown unchanged by the outer catch.
            try {
                commitCoordinator.commitForConfirm(order, items);
            } catch (RuntimeException commitEx) {
                log.error("Confirm commit failed for order {}", orderId, commitEx);
                throw BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId);
            }

            order.setConfirmedAt(Instant.now());
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            orderEventPublisher.publishStatusChanged(order);

            OrderResponse response = orderMapper.toResponse(order, items);
            if (idempotencyKey != null)
                idempotencyService.complete(idempotencyKey, actor, response, 200);
            return response;
        } catch (RuntimeException ex) {
            // begin() throws are deliberately OUTSIDE this try: a collision 409 must
            // never delete another execution's in-flight row (same as createOrder).
            if (idempotencyKey != null)
                idempotencyService.abort(idempotencyKey, actor, requestHash);
            if (!(ex instanceof BusinessException)) {  // infra failure → 409 ORD-4011, order stays PENDING
                // Wrap drops the root cause — log it so the infra failure stays diagnosable
                log.error("Confirm commit failed for order {}", orderId, ex);
                throw BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId);
            }
            throw ex;  // state-machine guard etc. rethrown unchanged
        }
    }

    @Override
    @Transactional
    public OrderResponse shipOrder(UUID orderId) {
        return transitionStatus(orderId, OrderStatus.SHIPPED, () -> Instant.now());
    }

    @Override
    @Transactional
    public OrderResponse deliverOrder(UUID orderId) {
        return transitionStatus(orderId, OrderStatus.DELIVERED, () -> Instant.now());
    }

    private OrderResponse transitionStatus(UUID orderId, OrderStatus to,
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
        Page<Order> page = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return withItems(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findAll(OrderStatus status, Pageable pageable) {
        Page<Order> page = (status != null)
            ? orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            : orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        return withItems(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID userId, UUID productId, Pageable pageable) {
        return orderItemRepository.findDeliveredByUserAndProduct(userId, productId, pageable)
            .map(orderMapper::toItemResponse);
    }

    /**
     * Batch-loads order items in ONE query for the whole page (review finding M4 —
     * the per-row {@code findByOrderId} was an N+1: a page of 20 issued 21 queries).
     */
    private Page<OrderResponse> withItems(Page<Order> page) {
        List<UUID> orderIds = page.map(Order::getId).getContent();
        Map<UUID, List<OrderItem>> itemsByOrder = orderIds.isEmpty()
            ? Map.of()
            : orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return page.map(order -> orderMapper.toResponse(
            order, itemsByOrder.getOrDefault(order.getId(), List.of())));
    }
}