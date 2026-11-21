package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.client.PaymentServiceClient;
import com.shop.orderservice.client.RefundOutcome;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderReturn;
import com.shop.orderservice.repository.OrderReturnRepository;
import com.shop.orderservice.service.OrderReturnService;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderReturnConcurrentReviewIT extends AbstractOrderServiceIT {

    private static final long REFUND_MILLIS = 600L;

    @MockitoBean
    private PaymentServiceClient paymentServiceClient;

    @Autowired
    private OrderReturnService orderReturnService;

    @Autowired
    private OrderReturnRepository orderReturnRepository;

    private UUID orderId;
    private UUID returnId;

    @BeforeEach
    void seedDeliveredOrderWithAPendingReturn() {
        Order order = Order.builder()
                .userId(UUID.randomUUID())
                .status(OrderStatus.DELIVERED)
                .subtotal(new BigDecimal("120.00"))
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(new BigDecimal("120.00"))
                .recipientName("Alice")
                .phoneNumber("0912345678")
                .shippingAddress("123 Main St")
                .build();
        orderId = orderRepository.saveAndFlush(order).getId();

        OrderReturn orderReturn = OrderReturn.builder()
                .order(order)
                .userId(order.getUserId())
                .reason("Damaged on arrival")
                .refundAmount(new BigDecimal("120.00"))
                .currency(order.getCurrency())
                .status(ReturnStatus.REQUESTED)
                .build();
        returnId = orderReturnRepository.saveAndFlush(orderReturn).getId();
    }

    @Test
    void anApprovalAndARejectionOfTheSameReturnCannotBothBeDecided() throws Exception {
        CountDownLatch moneyMoved = new CountDownLatch(1);
        when(paymentServiceClient.refundByOrderId(any(), any(), any(), any())).thenAnswer(invocation -> {
            moneyMoved.countDown();
            try {
                Thread.sleep(REFUND_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return RefundOutcome.REFUNDED;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        int decisions = 0;
        try {
            Future<Throwable> approval = pool.submit(() -> review(ReturnStatus.APPROVED));
            assertThat(moneyMoved.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> rejection = pool.submit(() -> review(ReturnStatus.REJECTED));

            for (Future<Throwable> outcome : List.of(approval, rejection)) {
                Throwable failure = outcome.get(30, TimeUnit.SECONDS);
                if (failure == null) {
                    decisions++;
                } else {
                    failures.add(failure);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(decisions)
                .as("the review of the return must be serialised so that only one decision is applied")
                .isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(BusinessException.class);
        verify(paymentServiceClient, times(1)).refundByOrderId(any(), any(), any(), any());

        OrderReturn reloaded = orderReturnRepository.findById(returnId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("the record of the return must describe the money that moved")
                .isEqualTo(ReturnStatus.REFUNDED);
    }

    private Throwable review(ReturnStatus status) {
        try {
            orderReturnService.reviewReturn(returnId, "admin_user", new OrderReturnReviewRequest(status, "reviewed"));
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }
}
