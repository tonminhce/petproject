package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.mapper.CartMapper;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartServiceImpl implements CartService {

    private static final int MAX_QUANTITY_PER_LINE = 99;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductServiceClient productClient;
    private final CartMapper cartMapper;

    @Override
    // P0-3 — NOT @Transactional(readOnly=true). getOrCreateCart may INSERT for new users;
    // readOnly connection would reject INSERT → 500 on first GET. This is a write (auto-create).
    @Transactional
    public CartResponse getMyCart(UUID userId) {
        Cart cart = getOrCreateCart(userId);
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        return cartMapper.toResponse(cart, items);
    }

    @Override
    @Transactional
    public CartResponse addItem(UUID userId, CartItemAddRequest request) {
        Cart cart = getOrCreateCart(userId);
        ProductSnapshot snapshot = productClient.getProduct(request.productId());
        if (snapshot == null) throw BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, request.productId());

        CartItem existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId()).orElse(null);
        int newQuantity;
        if (existing != null) {
            newQuantity = existing.getQuantity() + request.quantity();
            if (newQuantity > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            existing.setQuantity(newQuantity);
            existing.setProductTitle(snapshot.title());
            existing.setUnitPrice(snapshot.unitPrice());
            cartItemRepository.save(existing);
        } else {
            newQuantity = request.quantity();
            if (newQuantity > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            CartItem item = CartItem.builder()
                .cartId(cart.getId())
                .productId(request.productId())
                .productTitle(snapshot.title())
                .unitPrice(snapshot.unitPrice())
                .quantity(request.quantity())
                .build();
            cartItemRepository.save(item);
        }
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
        return cartMapper.toResponse(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Override
    @Transactional
    public CartResponse updateItem(UUID userId, UUID cartItemId, CartItemUpdateRequest request) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId));
        if (!item.getCartId().equals(cart.getId())) {
            throw BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId);  // hide cross-user
        }
        if (request.quantity() == 0) {
            cartItemRepository.delete(item);
        } else {
            if (request.quantity() > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            item.setQuantity(request.quantity());
            cartItemRepository.save(item);
        }
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
        return cartMapper.toResponse(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Override
    @Transactional
    public void removeItem(UUID userId, UUID cartItemId) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId));
        if (!item.getCartId().equals(cart.getId())) {
            throw BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId);  // hide cross-user
        }
        cartItemRepository.delete(item);
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        Cart cart = cartRepository.findByUserIdAndDeletedFalse(userId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, userId));
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cart.markDeleted(userId.toString());
        cartRepository.save(cart);
    }

    private Cart getOrCreateCart(UUID userId) {
        Optional<Cart> existing = cartRepository.findByUserIdAndDeletedFalse(userId);
        if (existing.isPresent()) return existing.get();
        try {
            return cartRepository.save(Cart.builder()
                .userId(userId)
                .subtotal(BigDecimal.ZERO)
                .build());
        } catch (DataIntegrityViolationException ex) {
            // ponytail: concurrent first-GET race — another request won the INSERT.
            // Re-fetch the winner instead of bubbling 500. Unique index on user_id
            // (active cart) is the actual source of truth; application-side locking
            // would just shift the race.
            return cartRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalStateException(
                    "Cart race: PK conflict but row not found", ex));
        }
    }

    private BigDecimal calculateSubtotal(Cart cart) {
        return cartItemRepository.findByCartId(cart.getId()).stream()
            .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
