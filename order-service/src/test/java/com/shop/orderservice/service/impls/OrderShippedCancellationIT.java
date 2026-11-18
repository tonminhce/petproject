package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderReturn;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OrderReturnRepository;
import com.shop.orderservice.service.OrderReturnService;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderShippedCancellationIT extends AbstractOrderServiceIT {

    @Autowired
    private OrderReturnService orderReturnService;

    @Autowired
    private OrderReturnRepository orderReturnRepository;

    private UUID orderId;
    private UUID adminId;

    @BeforeEach
    void seedShippedOrder() {
        adminId = UUID.randomUUID();
        Order order = Order.builder()
                .userId(UUID.randomUUID())
                .status(OrderStatus.SHIPPED)
                .subtotal(new BigDecimal("120.00"))
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(new BigDecimal("120.00"))
                .recipientName("Alice")
                .phoneNumber("0912345678")
                .shippingAddress("123 Main St")
                .build();
        Order saved = orderRepository.saveAndFlush(order);
        orderId = saved.getId();
    }

    @Test
    void theCancellationCreatesTheReturnThatDrivesTheRefund() {
        orderService.cancelOrder(orderId, adminId,  true);

        Order cancelled = orderRepository.findById(orderId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        List<OrderReturn> returns = orderReturnRepository.findByOrderId(orderId);
        assertThat(returns).hasSize(1);
        OrderReturn orderReturn = returns.get(0);
        assertThat(orderReturn.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(orderReturn.getRefundAmount()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(orderReturn.getReason())
                .isEqualTo(OrderReturnServiceImpl.REASON_SHIPPED_CANCELLATION);
    }

    @Test
    void aSecondCancellationOfTheSameOrderIsRefusedWhileTheReturnIsPending() {
        orderService.cancelOrder(orderId, adminId,  true);

        assertThatThrownBy(() -> orderService.cancelOrder(orderId, adminId,  true))
                .isInstanceOf(BusinessException.class);

        assertThat(orderReturnRepository.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    void theReturnRecordNeedsAShippedOrder() {
        Order pending = Order.builder()
                .userId(UUID.randomUUID())
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .build();
        Order savedPending = orderRepository.saveAndFlush(pending);

        assertThatThrownBy(() -> orderReturnService.recordShippedCancellation(savedPending.getId(), adminId))
                .isInstanceOf(BusinessException.class);

        assertThat(orderReturnRepository.findByOrderId(savedPending.getId())).isEmpty();
    }
}
