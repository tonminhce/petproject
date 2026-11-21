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
import static org.mockito.Mockito.times;
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

    private void stubLockedReturn(OrderReturn reviewed) {
        when(orderReturnRepository.findOrderIdByReturnId(returnId)).thenReturn(Optional.of(orderId));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(reviewed));
    }

    @Test
    void reviewReturn_approve_refundsTheApprovedAmountInTheOrderCurrency() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        stubLockedReturn(existing);
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
        stubLockedReturn(existing);
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
        stubLockedReturn(existing);
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
        stubLockedReturn(existing);
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
        stubLockedReturn(existing);
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
        when(orderReturnRepository.findOrderIdByReturnId(returnId)).thenReturn(Optional.of(orderId));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(existing, alreadyRefunded));

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
        stubLockedReturn(existing);

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
        stubLockedReturn(existing);

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
        stubLockedReturn(existing);
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        verify(orderReturnRepository).findByOrderIdForUpdate(orderId);
        verify(orderReturnRepository, never()).findByOrderId(orderId);
    }

    @Test
    void reviewReturn_readsTheDecisionFromTheLockedReturnsOfTheOrder() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        stubLockedReturn(existing);
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId))
                .thenReturn(RefundOutcome.REFUNDED);

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "Approved by manager"));

        verify(orderReturnRepository).findByOrderIdForUpdate(orderId);
        verify(orderReturnRepository, never()).findById(returnId);
        verify(orderReturnRepository, never()).findByOrderId(orderId);
    }

    @Test
    void reviewReturn_refusesASecondDecisionOnTheSameReturn() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn reviewed = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        reviewed.setStatus(ReturnStatus.REFUNDED);
        stubLockedReturn(reviewed);

        assertThatThrownBy(() -> service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.REJECTED, "Rejected by manager")))
                .isInstanceOf(BusinessException.class);

        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
        verify(orderReturnRepository, never()).save(any(OrderReturn.class));
    }

    @Test
    void theReviewedReturnIsLockedWithTheOtherReturnsOfItsOrder() {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn existing = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        stubLockedReturn(existing);
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reviewReturn(returnId, "admin_user",
                new OrderReturnReviewRequest(ReturnStatus.REJECTED, "Rejected by manager"));

        verify(orderReturnRepository).findByOrderIdForUpdate(orderId);
        verify(orderReturnRepository, never()).findById(returnId);
        verify(paymentServiceClient, never()).refundByOrderId(any(), any(), any(), any());
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

    @Test
    void anApprovalAndARejectionRacingOnTheSameReturnProduceOneDecisionAndTheMoneyMovesOnce() throws Exception {
        Order order = orderWithTotal(BigDecimal.valueOf(200), "VND");
        OrderReturn shared = requestedReturn(order, BigDecimal.valueOf(100), "VND");
        java.util.concurrent.locks.ReentrantLock databaseLock = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.CountDownLatch bothEntered = new java.util.concurrent.CountDownLatch(2);
        stubLockedReturnWithLock(shared, databaseLock, bothEntered);
        when(orderReturnRepository.save(any(OrderReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentServiceClient.refundByOrderId(orderId, BigDecimal.valueOf(100), "VND", returnId))
                .thenAnswer(invocation -> {
                    Thread.sleep(120);
                    return RefundOutcome.REFUNDED;
                });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> approval = pool.submit(() -> service.reviewReturn(returnId, "admin",
                new OrderReturnReviewRequest(ReturnStatus.APPROVED, "approve")));
            java.util.concurrent.Future<?> rejection = pool.submit(() -> service.reviewReturn(returnId, "admin",
                new OrderReturnReviewRequest(ReturnStatus.REJECTED, "reject")));
            java.util.List<Throwable> failures = new java.util.ArrayList<>();
            int decided = 0;
            for (java.util.concurrent.Future<?> outcome : java.util.List.of(approval, rejection)) {
                try {
                    outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    decided++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    failures.add(failure.getCause());
                }
            }
            assertThat(decided).as("exactly one review decision is applied").isEqualTo(1);
            assertThat(failures).as("the other decision is refused with a business failure").hasSize(1);
            verify(paymentServiceClient, times(1)).refundByOrderId(any(), any(), any(), any());
            assertThat(shared.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        } finally {
            pool.shutdownNow();
        }
    }

    private void stubLockedReturnWithLock(OrderReturn existing,
            java.util.concurrent.locks.Lock databaseLock, java.util.concurrent.CountDownLatch entered) {
        when(orderReturnRepository.findOrderIdByReturnId(returnId)).thenReturn(java.util.Optional.of(orderId));
        when(orderReturnRepository.findByOrderIdForUpdate(orderId)).thenAnswer(invocation -> {
            databaseLock.lock();
            try {
                entered.countDown();
                Thread.sleep(150);
                return java.util.List.of(existing);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return java.util.List.of(existing);
            } finally {
                databaseLock.unlock();
            }
        });
    }
}
