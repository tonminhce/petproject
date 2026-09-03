package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReturnServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderReturnRepository orderReturnRepository;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    private OrderReturnServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OrderReturnServiceImpl(orderRepository, orderReturnRepository, paymentServiceClient);
    }

    @Test
    void requestReturn_success() {
        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.DELIVERED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturn saved = OrderReturn.builder()
                .id(returnId)
                .order(order)
                .userId(userId)
                .reason("Damaged package")
                .refundAmount(BigDecimal.valueOf(100))
                .status(ReturnStatus.REQUESTED)
                .build();
        when(orderReturnRepository.save(any(OrderReturn.class))).thenReturn(saved);

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Damaged package", "Broken seal", BigDecimal.valueOf(100));

        OrderReturnResponse response = service.requestReturn(userId, orderId, request);

        assertThat(response.id()).isEqualTo(returnId);
        assertThat(response.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(response.refundAmount()).isEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    void requestReturn_undeliveredOrder_throwsException() {
        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.CONFIRMED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Damaged package", "Broken seal", BigDecimal.valueOf(100));

        assertThatThrownBy(() -> service.requestReturn(userId, orderId, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void requestReturn_notOwnedByUser_throwsException() {
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID()) // different user
                .status(OrderStatus.DELIVERED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Damaged package", "Broken seal", BigDecimal.valueOf(100));

        assertThatThrownBy(() -> service.requestReturn(userId, orderId, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reviewReturn_approve_triggersRefund() {
        Order order = Order.builder().id(orderId).userId(userId).build();
        OrderReturn existing = OrderReturn.builder()
                .id(returnId)
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(100))
                .status(ReturnStatus.REQUESTED)
                .build();
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderReturnReviewRequest review = new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager");

        OrderReturnResponse response = service.reviewReturn(returnId, "admin_user", review);

        assertThat(response.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(response.reviewedBy()).isEqualTo("admin_user");
        verify(paymentServiceClient).refundByOrderId(orderId);
    }

    @Test
    void findMyReturns_returnsPagedData() {
        Order order = Order.builder().id(orderId).userId(userId).build();
        OrderReturn r = OrderReturn.builder()
                .id(returnId).order(order).userId(userId).reason("R").refundAmount(BigDecimal.ONE).status(ReturnStatus.REQUESTED).build();
        Pageable pageable = PageRequest.of(0, 10);
        when(orderReturnRepository.findByUserId(userId, pageable)).thenReturn(new PageImpl<>(List.of(r), pageable, 1));

        PageResponse<OrderReturnResponse> result = service.findMyReturns(userId, pageable);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
