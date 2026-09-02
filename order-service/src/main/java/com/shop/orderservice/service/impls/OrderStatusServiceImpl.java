package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.service.OrderStatusService;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
public class OrderStatusServiceImpl implements OrderStatusService {

    // H12 — SHIPPED → CANCELLED is now allowed. The pre-fix design blocked
    // it, but admin tooling needs to cancel post-shipment orders for
    // carrier-side issues (lost parcels, fraud chargebacks, recall flows).
    // DELIVERED → CANCELLED is still terminal: post-delivery cancel belongs
    // to the refund / return flow, not the cancel-state machine.
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
        OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED,   OrderStatus.CANCELLED),
        OrderStatus.SHIPPED,   EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
        OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
        OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    @Override
    public void validateTransition(OrderStatus from, OrderStatus to) {
        if (!ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to)) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE_TRANSITION, from, to);
        }
    }
}
