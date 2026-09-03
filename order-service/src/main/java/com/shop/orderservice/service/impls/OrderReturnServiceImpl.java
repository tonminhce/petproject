package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnCreateRequest;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderReturn;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OrderReturnRepository;
import com.shop.orderservice.service.OrderReturnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderReturnServiceImpl implements OrderReturnService {

    private final OrderRepository orderRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final PaymentServiceClient paymentServiceClient;

    @Override
    @Transactional
    public OrderReturnResponse requestReturn(UUID userId, UUID orderId, OrderReturnCreateRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        if (!order.getUserId().equals(userId)) {
            throw BusinessException.forbidden("order.not.owned.by.user");
        }

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw BusinessException.badRequest("order.return.only.delivered");
        }

        if (request.refundAmount().compareTo(order.getTotal()) > 0) {
            throw BusinessException.badRequest("order.return.refund.exceeds.total");
        }

        OrderReturn orderReturn = OrderReturn.builder()
                .order(order)
                .userId(userId)
                .reason(request.reason())
                .description(request.description())
                .refundAmount(request.refundAmount())
                .status(ReturnStatus.REQUESTED)
                .build();

        OrderReturn saved = orderReturnRepository.save(orderReturn);
        log.info("Created return request {} for order {} by user {}", saved.getId(), orderId, userId);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderReturnResponse> findByOrderId(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        if (!order.getUserId().equals(userId)) {
            throw BusinessException.forbidden("order.not.owned.by.user");
        }

        return orderReturnRepository.findByOrderId(orderId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderReturnResponse> findMyReturns(UUID userId, Pageable pageable) {
        Page<OrderReturn> page = orderReturnRepository.findByUserId(userId, pageable);
        List<OrderReturnResponse> items = page.getContent().stream()
                .map(this::mapToResponse)
                .toList();
        return PageResponse.of(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Override
    @Transactional
    public OrderReturnResponse reviewReturn(UUID returnId, String adminUsername, OrderReturnReviewRequest request) {
        OrderReturn orderReturn = orderReturnRepository.findById(returnId)
                .orElseThrow(() -> BusinessException.notFound("order.return.not.found", returnId));

        if (orderReturn.getStatus() != ReturnStatus.REQUESTED) {
            throw BusinessException.badRequest("order.return.already.reviewed");
        }

        orderReturn.setStatus(request.status());
        orderReturn.setAdminNotes(request.adminNotes());
        orderReturn.setReviewedBy(adminUsername);
        orderReturn.setReviewedAt(Instant.now());

        if (request.status() == ReturnStatus.APPROVED || request.status() == ReturnStatus.REFUNDED) {
            paymentServiceClient.refundByOrderId(orderReturn.getOrder().getId());
            orderReturn.setStatus(ReturnStatus.REFUNDED);
        }

        OrderReturn updated = orderReturnRepository.save(orderReturn);
        log.info("Admin {} reviewed return {} with decision {}", adminUsername, returnId, updated.getStatus());
        return mapToResponse(updated);
    }

    private OrderReturnResponse mapToResponse(OrderReturn entity) {
        return new OrderReturnResponse(
                entity.getId(),
                entity.getOrder().getId(),
                entity.getUserId(),
                entity.getReason(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getRefundAmount(),
                entity.getAdminNotes(),
                entity.getReviewedBy(),
                entity.getReviewedAt(),
                entity.getCreatedAt()
        );
    }
}
