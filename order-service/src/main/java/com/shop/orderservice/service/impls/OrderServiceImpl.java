package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.internal.PaymentStatusSnapshot;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.dto.response.OrderTrackingResponse;
import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.service.CompensationFailureService;
import com.shop.orderservice.service.IdempotencyService;
import com.shop.orderservice.service.OrderCommitCoordinator;
import com.shop.orderservice.service.OrderConfirmMetrics;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.OrderService;
import com.shop.orderservice.service.OrderReturnService;
import com.shop.orderservice.service.OrderStatusService;
import com.shop.orderservice.service.StockReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /** Wrong phone numbers tolerated before the phone-only tracking path locks. */
    static final int MAX_TRACK_PHONE_FAILURES = 5;

    /** How long the phone-only tracking path stays locked for one order. */
    static final Duration TRACK_PHONE_LOCK = Duration.ofMinutes(15);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
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
    private final OrderReturnService orderReturnService;
    private final CompensationFailureService compensationFailureService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PromotionServiceClient promotionClient,
            PaymentServiceClient paymentClient,
            StockReservationService stockReservationService,
            OrderEventPublisher orderEventPublisher,
            IdempotencyService idempotencyService,
            OrderStatusService orderStatusService,
            OrderMapper orderMapper,
            ObjectMapper objectMapper,
            OrderCommitCoordinator commitCoordinator,
            OrderConfirmMetrics confirmMetrics,
            OrderCreateSaga orderCreateSaga,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) OrderReturnService orderReturnService,
            @Autowired(required = false) CompensationFailureService compensationFailureService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.promotionClient = promotionClient;
        this.paymentClient = paymentClient;
        this.stockReservationService = stockReservationService;
        this.orderEventPublisher = orderEventPublisher;
        this.idempotencyService = idempotencyService;
        this.orderStatusService = orderStatusService;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.commitCoordinator = commitCoordinator;
        this.confirmMetrics = confirmMetrics;
        this.orderCreateSaga = orderCreateSaga;
        this.orderReturnService = orderReturnService;
        this.compensationFailureService = compensationFailureService;
        this.transactionTemplate = (transactionManager != null) ? new TransactionTemplate(transactionManager) : null;
    }

    public OrderServiceImpl(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PromotionServiceClient promotionClient,
            PaymentServiceClient paymentClient,
            StockReservationService stockReservationService,
            OrderEventPublisher orderEventPublisher,
            IdempotencyService idempotencyService,
            OrderStatusService orderStatusService,
            OrderMapper orderMapper,
            ObjectMapper objectMapper,
            OrderCommitCoordinator commitCoordinator,
            OrderConfirmMetrics confirmMetrics,
            OrderCreateSaga orderCreateSaga) {
        this(orderRepository, orderItemRepository, promotionClient, paymentClient,
                stockReservationService, orderEventPublisher, idempotencyService,
                orderStatusService, orderMapper, objectMapper, commitCoordinator,
                confirmMetrics, orderCreateSaga, (PlatformTransactionManager) null, null, null);
    }

    @Override
    public OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Idempotency pre-insert (in-flight row commits in its own TX, before the saga).
        String requestHash = hash(request);
        Optional<OrderResponse> cached = idempotencyService.begin(idempotencyKey, userId.toString(), requestHash);
        if (cached.isPresent()) return cached.get();  // REPLAY — do not re-run the saga

        // 2. H13 — delegate to OrderCreateSaga (sibling bean) so the @Transactional
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

    @Override
    @Transactional
    public OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
        }

        if (!isAdmin && order.getStatus() != OrderStatus.PENDING) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }
        orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);

        // releaseCommitted endpoint (restocking) on admin cancel. Without it, admin
        if (order.getStatus() == OrderStatus.PENDING) {
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(Objects::nonNull)
                .toList();
            releaseAllReservationsById(orderId, reservationIds);
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

            // release() path would reject the row because it's COMMITTED, not
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(Objects::nonNull)
                .toList();
            releaseAllCommittedReservationsById(orderId, reservationIds);
            if (order.getPromotionReservationId() != null) {
                try {
                    promotionClient.releaseCommitted(order.getPromotionReservationId());
                } catch (Exception pex) {
                    log.error("Failed to release committed promotion reservation {} during admin cancel",
                        order.getPromotionReservationId(), pex);
                }
            }
            try {
                paymentClient.refundByOrderId(orderId);
            } catch (Exception rex) {
                log.error("Failed to trigger payment refund during admin cancel for order {}", orderId, rex);
            }
        } else if (order.getStatus() == OrderStatus.SHIPPED && isAdmin) {
            // H12: the admin cancels a post-shipment order (carrier loss, fraud,
            // recall). The goods are in transit, so the stock must NOT be moved
            // back here - that would put goods back on sale that may still be
            // delivered - and the refund must NOT be fired here either: the
            // return flow drives it, exactly once, through the admin review,
            // which is also where the refund failure is handled. Without this
            // the cancellation was a bare status change: no record, no refund.
            orderReturnService.recordShippedCancellation(orderId, userId);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        // NO markDeleted — cancelled orders must remain in user/admin history (rev 2 fix)
        orderRepository.save(order);

        orderEventPublisher.publishCancelled(order);
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    /**
     * A failed release during a cancel must leave a durable trace: the order
     * row is cancelled right after, so a swallowed failure would orphan the
     * hold with nothing pointing at it.
     */
    private void recordCompensationFailure(UUID orderId, UUID reservationId, Exception ex) {
        if (compensationFailureService == null) {
            return;
        }
        compensationFailureService.record(orderId, reservationId,
                FailedCompensation.KIND_RESERVATION_RELEASE, ex.getMessage());
    }

    private void releaseAllReservationsById(UUID orderId, List<UUID> reservationIds) {
        for (UUID id : reservationIds) {
            try {
                stockReservationService.release(id);
            } catch (Exception ex) {
                recordCompensationFailure(orderId, id, ex);
                log.error("Failed to release reservation {} during cancel", id, ex);
            }
        }
    }

    /** H9 — admin cancel CONFIRMED path; restocks via releaseCommitted. */
    private void releaseAllCommittedReservationsById(UUID orderId, List<UUID> reservationIds) {
        for (UUID id : reservationIds) {
            try {
                stockReservationService.releaseCommitted(id);
            } catch (Exception ex) {
                recordCompensationFailure(orderId, id, ex);
                log.error("Failed to release committed reservation {} during admin cancel", id, ex);
            }
        }
    }

    // SERVICE → service:<azp>) — stored verbatim on idempotency rows.
    @Override
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

            // payment-service flag on, confirm requires a CAPTURED payment. ANY
            // client failure also yields ORD-4012 (fail-closed — a payment we
            // could not verify must block confirm, and the raw exception never
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

            // must not leak as 503). validateTransition's guard sits BEFORE this inner
            try {
                commitCoordinator.commitForConfirm(order, items);
            } catch (RuntimeException commitEx) {
                log.error("Confirm commit failed for order {}", orderId, commitEx);
                throw BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId);
            }

            OrderResponse response = (transactionTemplate != null)
                ? transactionTemplate.execute(tx -> {
                    order.setConfirmedAt(Instant.now());
                    order.setStatus(OrderStatus.CONFIRMED);
                    orderRepository.save(order);
                    orderEventPublisher.publishStatusChanged(order);
                    return orderMapper.toResponse(order, items);
                })
                : applyConfirmedStatus(order, items);

            if (idempotencyKey != null)
                idempotencyService.complete(idempotencyKey, actor, response, 200);
            return response;
        } catch (RuntimeException ex) {
            // begin() throws are deliberately OUTSIDE this try: a collision 409 must
            // never delete another execution's in-flight row (same as createOrder).
            if (idempotencyKey != null)
                idempotencyService.abort(idempotencyKey, actor, requestHash);
            if (!(ex instanceof BusinessException)) {  // infra failure → 409 ORD-4011, order stays PENDING
                log.error("Confirm commit failed for order {}", orderId, ex);
                throw BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId);
            }
            throw ex;  // state-machine guard etc. rethrown unchanged
        }
    }

    private OrderResponse applyConfirmedStatus(Order order, List<OrderItem> items) {
        order.setConfirmedAt(Instant.now());
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        orderEventPublisher.publishStatusChanged(order);
        return orderMapper.toResponse(order, items);
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
                                          Supplier<Instant> timestampSupplier) {
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

    /**
     * The legacy phone-number path of the tracking endpoint.
     *
     * <p>The phone number is a weak factor: it is low entropy, it is often
     * known to third parties, and the order id - the other half of the old
     * pair - leaks routinely through links, screenshots, support tickets and
     * logs. An unauthenticated caller who knows an order id could grind
     * through candidate numbers. So the path is kept for the callers that
     * have no tracking token yet, but every wrong number is counted and the
     * order is locked out of the phone path for a cool-down window after a
     * few failures. A wrong token and a wrong phone produce the SAME error,
     * so the endpoint is not an oracle for "which half was wrong".</p>
     */
    private void authorizeByPhone(Order order, String phoneNumber) {
        Instant now = Instant.now();
        if (order.getTrackLockedUntil() != null && order.getTrackLockedUntil().isAfter(now)) {
            log.warn("Rejected tracking lookup for order {}: phone path locked until {}",
                    order.getId(), order.getTrackLockedUntil());
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, order.getId());
        }
        if (order.getPhoneNumber() == null
                || !order.getPhoneNumber().trim().equalsIgnoreCase(phoneNumber.trim())) {
            int failures = (order.getTrackFailures() == null ? 0 : order.getTrackFailures()) + 1;
            order.setTrackFailures(failures);
            if (failures >= MAX_TRACK_PHONE_FAILURES) {
                order.setTrackLockedUntil(now.plus(TRACK_PHONE_LOCK));
                order.setTrackFailures(0);
                log.warn("Locked the phone-only tracking path of order {} until {} after {} wrong numbers",
                        order.getId(), order.getTrackLockedUntil(), failures);
            }
            orderRepository.save(order);
            log.warn("Rejected tracking lookup for order {}: wrong phone number (failure #{})",
                    order.getId(), failures);
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, order.getId());
        }
        if (order.getTrackFailures() != null && order.getTrackFailures() > 0) {
            order.setTrackFailures(0);
            order.setTrackLockedUntil(null);
            orderRepository.save(order);
        }
    }

    @Override
    // noRollbackFor: the abuse brake of the phone path is written by the very
    // rejection it records, and BusinessException is a RuntimeException - the
    // default rollback would discard the failure counter and the lockout with
    // the error, so the brake would never engage. Every other runtime failure
    // still rolls back.
    @Transactional(noRollbackFor = BusinessException.class)
    public OrderTrackingResponse trackOrder(UUID orderId, String trackingToken, String phoneNumber) {
        if (orderId == null) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
        }
        boolean tokenGiven = trackingToken != null && !trackingToken.isBlank();
        boolean phoneGiven = phoneNumber != null && !phoneNumber.isBlank();
        if (!tokenGiven && !phoneGiven) {
            throw BusinessException.badRequest("order.track.credentials_required");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        if (tokenGiven) {
            // The tracking code is a random capability the customer holds, so
            // it authorises the lookup on its own - no phone number needed.
            if (order.getTrackingCode() == null
                    || !order.getTrackingCode().toString().equalsIgnoreCase(trackingToken.trim())) {
                log.warn("Rejected tracking lookup for order {}: wrong tracking token", orderId);
                throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
            }
        } else {
            authorizeByPhone(order, phoneNumber);
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        List<OrderItemResponse> itemResponses = items.stream()
                .map(orderMapper::toItemResponse)
                .toList();

        return new OrderTrackingResponse(
                order.getId(),
                order.getStatus(),
                order.getRecipientName(),
                order.getShippingAddress(),
                order.getTotal(),
                order.getCreatedAt(),
                itemResponses
        );
    }
}