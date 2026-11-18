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
import lombok.extern.slf4j.Slf4j;
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
import java.util.regex.Pattern;

@RestController
@RequestMapping(ApiPaths.CARTS)
@RequiredArgsConstructor
@Slf4j
public class CartController {

    private static final Pattern GUEST_CART_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
    public ApiResponse<CartResponse> mergeCart(
            @RequestHeader(value = "X-Guest-Cart-Id", required = false) String guestCartIdHeader,
            @RequestParam(value = "guestCartId", required = false) String guestCartIdParam) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        String guestCartId = resolveMergeGuestCartId(guestCartIdHeader, guestCartIdParam);
        UUID guestUserId = guestUserId(guestCartId);
        log.info("Merging guest cart {} into user {}", guestCartId, userId);
        return ApiResponse.ok(cartService.mergeCart(userId, guestUserId), "Cart merged successfully");
    }

    private String resolveMergeGuestCartId(String header, String param) {
        String fromHeader = (header == null || header.isBlank()) ? null : header.trim();
        String fromParam = (param == null || param.isBlank()) ? null : param.trim();
        if (fromHeader == null) {
            throw BusinessException.badRequest("cart.merge.guest_cart_id_required");
        }
        if (fromParam != null && !fromParam.equals(fromHeader)) {
            throw BusinessException.badRequest("cart.merge.guest_cart_id_mismatch");
        }
        return requireGuestCartIdFormat(fromHeader);
    }

    private UUID resolveCartUserId(String guestCartId) {
        var authOpt = AuthenticatedUser.current();
        if (authOpt.isPresent() && authOpt.get().id() != null && !authOpt.get().id().isBlank()) {
            return UUID.fromString(authOpt.get().id());
        }
        if (guestCartId != null && !guestCartId.isBlank()) {
            return guestUserId(requireGuestCartIdFormat(guestCartId.trim()));
        }
        throw BusinessException.unauthorized("auth.unauthorized");
    }

    private static String requireGuestCartIdFormat(String guestCartId) {
        if (!GUEST_CART_ID.matcher(guestCartId).matches()) {
            throw BusinessException.badRequest("cart.guest_cart_id_invalid");
        }
        return guestCartId;
    }

    private static UUID guestUserId(String guestCartId) {
        return UUID.nameUUIDFromBytes(("guest:" + guestCartId).getBytes(StandardCharsets.UTF_8));
    }
}
