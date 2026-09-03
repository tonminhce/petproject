package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.CARTS)
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/me")
    public ApiResponse<CartResponse> getMyCart(
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartId) {
        return ApiResponse.ok(cartService.getMyCart(resolveCartUserId(guestCartId)));
    }

    @PostMapping("/me/items")
    public ApiResponse<CartResponse> addItem(
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartId,
            @Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(cartService.addItem(resolveCartUserId(guestCartId), request), "Item added to cart");
    }

    @PutMapping("/me/items/{cartItemId}")
    public ApiResponse<CartResponse> updateItem(
            @PathVariable UUID cartItemId,
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(cartService.updateItem(resolveCartUserId(guestCartId), cartItemId, request), "Cart item updated");
    }

    @DeleteMapping("/me/items/{cartItemId}")
    public ApiResponse<Void> removeItem(
            @PathVariable UUID cartItemId,
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartId) {
        cartService.removeItem(resolveCartUserId(guestCartId), cartItemId);
        return ApiResponse.message("Item removed from cart");
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> clearCart(
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartId) {
        cartService.clearCart(resolveCartUserId(guestCartId));
        return ApiResponse.message("Cart cleared");
    }

    @PostMapping("/merge")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CartResponse> mergeCart(@RequestParam String guestCartId) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        UUID guestUserId = UUID.nameUUIDFromBytes(("guest:" + guestCartId.trim()).getBytes(StandardCharsets.UTF_8));
        return ApiResponse.ok(cartService.mergeCart(userId, guestUserId), "Cart merged successfully");
    }

    private UUID resolveCartUserId(String guestCartId) {
        var authOpt = AuthenticatedUser.current();
        if (authOpt.isPresent() && authOpt.get().id() != null && !authOpt.get().id().isBlank()) {
            return UUID.fromString(authOpt.get().id());
        }
        if (guestCartId != null && !guestCartId.isBlank()) {
            return UUID.nameUUIDFromBytes(("guest:" + guestCartId.trim()).getBytes(StandardCharsets.UTF_8));
        }
        throw BusinessException.unauthorized("auth.unauthorized");
    }
}
