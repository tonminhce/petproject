package com.shop.orderservice.mapper;

import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream().map(this::toItemResponse).toList();
        return new OrderResponse(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            itemResponses,
            order.getSubtotal(),
            order.getTaxAmount(),
            order.getDiscountAmount(),
            order.getTotal(),
            order.getCouponCode(),
            order.getCreatedAt(),
            order.getConfirmedAt(),
            order.getShippedAt(),
            order.getDeliveredAt(),
            order.getCancelledAt()
        );
    }

    public OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
            item.getProductId(),
            item.getProductTitle(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getLineTotal()
        );
    }
}
