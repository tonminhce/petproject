package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.RefundOutcome;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnCreateRequest;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderReturn;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OrderReturnRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
                .userId(UUID.randomUUID())
                .status(OrderStatus.DELIVERED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Damaged package", "Broken seal", BigDecimal.valueOf(100));

        assertThatThrownBy(() -> service.requestReturn(userId, orderId, request))
                .isInstanceOf(BusinessException.class);
    }

    private Order orderWithTotal(BigDecimal total, String currency) {
        return Order.builder().id(orderId).userId(userId).total(total).currency(currency).build();
    }

    private OrderReturn requestedReturn(Order order, BigDecimal refundAmount, String currency) {
        return OrderReturn.builder()
                .id(returnId)
                .order(order)
                .userId(userId)
                .refundAmount(refundAmount)
                .currency(currency)
                .status(ReturnStatus.REQUESTED)
                .build();
    }

    @Test
    void reviewReturn_approve_refundsTheApprovedAmountInTheOrderCurrency() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        OrderReturnReviewRequest review = new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager");

        OrderReturnResponse response = service.reviewReturn(returnId, "admin_user", review);

        assertThat(response.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(response.reviewedBy()).isEqualTo("admin_user");
        verify(paymentServiceClient).refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId);
    }

    @Test
    void reviewReturn_approve_refundsOnlyTheApprovedAmountNotTheWholeOrder() {
        Order order = orderWithTotal(new BigDecimal("2499.00"), "VND");
        OrderReturn existing = requestedReturn(order, new BigDecimal("100.00"), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, new BigDecimal("100.00"), "VND", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        verify(paymentServiceClient).refundByOrderId(orderId, new BigDecimal("100.00"), "VND", returnId);
        verify(paymentServiceClient, never()).refundByOrderId(orderId);
    }

    @Test
    void reviewReturn_approve_refundsInTheCurrencyOfTheOrder() {
        Order order = orderWithTotal(new BigDecimal("2499.00"), "USD");
        OrderReturn existing = requestedReturn(order, new BigDecimal("100.00"), "USD");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, new BigDecimal("100.00"), "USD", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        verify(paymentServiceClient).refundByOrderId(orderId, new BigDecimal("100.00"), "USD", returnId);
    }

    @Test
    void reviewReturn_theRetryAfterATimeoutFindsTheConfirmedRefundAndMarksTheReturnRefunded() {
        Order order = orderWithTotal(new BigDecimal("2499.00"), "VND");
        OrderReturn existing = requestedReturn(order, new BigDecimal("100.00"), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, new BigDecimal("100.00"), "VND", returnId))
                .thenReturn(RefundOutcome.ALREADY_REFUNDED);

        OrderReturnResponse response = service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        assertThat(response.status()).isEqualTo(ReturnStatus.REFUNDED);
        verify(paymentServiceClient).refundByOrderId(orderId, new BigDecimal("100.00"), "VND", returnId);
    }

    @Test
    void reviewReturn_aTimeoutOfTheRefundIsPropagatedAndTheReturnIsNotMarkedRefunded() {
        Order order = orderWithTotal(new BigDecimal("2499.00"), "VND");
        OrderReturn existing = requestedReturn(order, new BigDecimal("100.00"), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(existing));
        when(paymentServiceClient.refundByOrderId(orderId, new BigDecimal("100.00"), "VND", returnId))
                .thenThrow(BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment"));

        assertThatThrownBy(() -> service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode()));

        verify(orderReturnRepository, never()).save(any(OrderReturn.class));
    }

    @Test
    void reviewReturn_approve_rejectsWhenTheApprovedAmountExceedsTheRemainingRefundableAmount() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        OrderReturn alreadyRefunded = OrderReturn.builder()
                .id(UUID.randomUUID())
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(150))
                .currency("VND")
                .status(ReturnStatus.REFUNDED)
                .build();
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(alreadyRefunded));

        assertThatThrownBy(() -> service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
        verify(paymentServiceClient, never()).refundByOrderId(any(UUID.class));
    }

    @Test
    void reviewReturn_approve_rejectsWhenTheReturnCurrencyIsNotTheOrderCurrency() {
        Order order = orderWithTotal(new BigDecimal("2499.00"), "USD");
        OrderReturn existing = requestedReturn(order, new BigDecimal("100.00"), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5010"));

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
        verify(paymentServiceClient, never()).refundByOrderId(any(UUID.class));
    }

    @Test
    void reviewReturn_approve_rejectsAnApprovedAmountThatIsNotPositive() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.ZERO, "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("PAY-5007"));

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
        verify(paymentServiceClient, never()).refundByOrderId(any(UUID.class));
    }

    @Test
    void reviewReturn_readsTheOtherReturnsOfTheOrderUnderAWriteLock() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        when(orderReturnRepository.findById(returnId)).thenReturn(Optional.of(existing));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(existing));
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        verify(orderReturnRepository).findByOrderIdForUpdate(orderId);
        verify(orderReturnRepository, never()).findByOrderId(orderId);
    }

    @Test
    void theReturnLookupThatGuardsTheRefundIsTakenUnderAWriteLock() throws Exception {
        Method method = OrderReturnRepository.class.getMethod("findByOrderIdForUpdate", UUID.class);

        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
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

    @Test
    void requestReturn_cumulativeRefundExceedsTotal_throwsBadRequest() {
        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.DELIVERED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturn approvedReturn = OrderReturn.builder()
                .id(UUID.randomUUID())
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(150))
                .status(ReturnStatus.APPROVED)
                .build();
        when(orderReturnRepository.findByOrderId(orderId)).thenReturn(List.of(approvedReturn));

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Defective item", "Screen broken", BigDecimal.valueOf(60));

        assertThatThrownBy(() -> service.requestReturn(userId, orderId, request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("exceeds the order total");
                    assertThat(ex.getMessage()).doesNotContain("order.return.refund.exceeds.total");
                });
    }

    @Test
    void requestReturn_cumulativeRefundWithinTotal_success() {
        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .status(OrderStatus.DELIVERED)
                .total(BigDecimal.valueOf(200))
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderReturn approvedReturn = OrderReturn.builder()
                .id(UUID.randomUUID())
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(80))
                .status(ReturnStatus.APPROVED)
                .build();
        OrderReturn refundedReturn = OrderReturn.builder()
                .id(UUID.randomUUID())
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(40))
                .status(ReturnStatus.REFUNDED)
                .build();
        OrderReturn rejectedReturn = OrderReturn.builder()
                .id(UUID.randomUUID())
                .order(order)
                .userId(userId)
                .refundAmount(BigDecimal.valueOf(50))
                .status(ReturnStatus.REJECTED)
                .build();
        when(orderReturnRepository.findByOrderId(orderId))
                .thenReturn(List.of(approvedReturn, refundedReturn, rejectedReturn));

        OrderReturn saved = OrderReturn.builder()
                .id(returnId)
                .order(order)
                .userId(userId)
                .reason("Defective part")
                .refundAmount(BigDecimal.valueOf(80))
                .status(ReturnStatus.REQUESTED)
                .build();
        when(orderReturnRepository.save(any(OrderReturn.class))).thenReturn(saved);

        OrderReturnCreateRequest request = new OrderReturnCreateRequest(
                "Defective part", "Connector loose", BigDecimal.valueOf(80));

        OrderReturnResponse response = service.requestReturn(userId, orderId, request);
        assertThat(response.id()).isEqualTo(returnId);
        assertThat(response.refundAmount()).isEqualTo(BigDecimal.valueOf(80));
    }
}
