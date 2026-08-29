package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.CARTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CartController {

    private final CartService cartService;

    @GetMapping("/me")
    public ApiResponse<CartResponse> getMyCart() {
        return ApiResponse.ok(cartService.getMyCart(currentUserId()));
    }

    @PostMapping("/me/items")
    public ApiResponse<CartResponse> addItem(@Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(cartService.addItem(currentUserId(), request), "Item added to cart");
    }

    @PutMapping("/me/items/{cartItemId}")
    public ApiResponse<CartResponse> updateItem(@PathVariable UUID cartItemId,
                                                  @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(cartService.updateItem(currentUserId(), cartItemId, request), "Cart item updated");
    }

    @DeleteMapping("/me/items/{cartItemId}")
    public ApiResponse<Void> removeItem(@PathVariable UUID cartItemId) {
        cartService.removeItem(currentUserId(), cartItemId);
        return ApiResponse.message("Item removed from cart");
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> clearCart() {
        cartService.clearCart(currentUserId());
        return ApiResponse.message("Cart cleared");
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
