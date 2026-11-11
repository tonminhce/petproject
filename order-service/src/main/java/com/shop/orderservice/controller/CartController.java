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

/**
 * The guest cart is addressed by an opaque id the browser holds
 * ({@code X-Guest-Cart-Id}); it maps deterministically to a guest user id.
 * That id is therefore a BEARER CAPABILITY: whoever presents it owns the
 * guest cart.
 *
 * <p>Two rules keep the capability from being guessable or leaked:</p>
 * <ul>
 *   <li>The id must be a canonical UUID. The server derives the guest user id
 *       by hashing whatever string it is given, so a client that chose a
 *       short or sequential id (1, 2, 3, a timestamp) would have made every
 *       guest cart in the shop enumerable to any caller who guessed the next
 *       value. A UUID carries 122 bits of entropy.</li>
 *   <li>The merge endpoint reads the id from the same header every other cart
 *       endpoint uses. A capability that travels in a query string lands in
 *       access logs, proxy logs, browser history and Referer headers - and a
 *       leaked id is enough to take over somebody else's guest cart. The
 *       query parameter is still accepted for clients that send both, but it
 *       only counts when it MATCHES the header, so a URL alone proves
 *       nothing.</li>
 * </ul>
 */
@RestController
@RequestMapping(ApiPaths.CARTS)
@RequiredArgsConstructor
@Slf4j
public class CartController {

    /** The guest cart id is an opaque capability: it must carry real entropy. */
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

    /**
     * The merge needs PROOF of the guest cart capability, not just the id.
     *
     * <p>The id is accepted from the header (as every other cart endpoint
     * does) or from the query parameter, but the query parameter alone is not
     * enough: an id that came out of an access log or a Referer header is
     * exactly the id an attacker would have. When both are present they must
     * agree, so a client cannot smuggle one id past the check by putting
     * another in the URL.</p>
     */
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

    /**
     * The guest cart id is a bearer capability, so it must carry enough
     * entropy to be unguessable. A client that chose a short or sequential
     * id would have made every guest cart in the shop enumerable.
     */
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
