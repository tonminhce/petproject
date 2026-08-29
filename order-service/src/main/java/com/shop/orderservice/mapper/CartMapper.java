package com.shop.orderservice.mapper;

import com.shop.orderservice.dto.response.CartItemResponse;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CartMapper {

    public CartResponse toResponse(Cart cart, List<CartItem> items) {
        List<CartItemResponse> itemResponses = items.stream()
            .map(this::toItemResponse)
            .toList();
        return new CartResponse(
            cart.getId(),
            cart.getUserId(),
            itemResponses,
            cart.getSubtotal(),
            cart.getCreatedAt(),
            cart.getUpdatedAt()
        );
    }

    public CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
            item.getId(),
            item.getProductId(),
            item.getProductTitle(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
