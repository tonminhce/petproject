package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductServiceClient productClient;
    @Mock CartMapper cartMapper;

    @InjectMocks CartServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID cartItemId = UUID.randomUUID();
    private final UUID otherCartId = UUID.randomUUID();
    private final ProductSnapshot snapshot =
        new ProductSnapshot(productId, "Widget", new BigDecimal("9.99"));

    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = Cart.builder().id(cartId).userId(userId).subtotal(BigDecimal.ZERO).build();
    }

    private CartResponse emptyResponse() {
        return new CartResponse(cartId, userId, List.of(), BigDecimal.ZERO, null, null);
    }

    // ---------- getMyCart ----------

    @Test
    void getMyCart_autoCreatesForNewUser() {
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenReturn(cart);
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartMapper.toResponse(cart, List.of())).thenReturn(emptyResponse());

        CartResponse result = service.getMyCart(userId);

        assertThat(result).isNotNull();
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void getMyCart_returnsExistingCart() {
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartMapper.toResponse(cart, List.of())).thenReturn(emptyResponse());

        CartResponse result = service.getMyCart(userId);

        assertThat(result).isNotNull();
        verify(cartRepository, never()).save(any(Cart.class));  // no auto-create
    }

    // ---------- addItem ----------

    @Test
    void addItem_addsNewLine() {
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(productClient.getProduct(productId)).thenReturn(snapshot);
        when(cartItemRepository.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartMapper.toResponse(cart, List.of())).thenReturn(emptyResponse());

        CartResponse result = service.addItem(userId,
            new CartItemAddRequest(productId, 2));

        assertThat(result).isNotNull();
        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        CartItem saved = captor.getValue();
        assertThat(saved.getProductId()).isEqualTo(productId);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void addItem_mergesExistingLine() {
        CartItem existing = CartItem.builder()
            .id(cartItemId).cartId(cartId).productId(productId)
            .productTitle("Widget").unitPrice(new BigDecimal("9.99")).quantity(3)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(productClient.getProduct(productId)).thenReturn(snapshot);
        when(cartItemRepository.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.of(existing));
        when(cartItemRepository.save(existing)).thenReturn(existing);
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartMapper.toResponse(cart, List.of())).thenReturn(emptyResponse());

        service.addItem(userId, new CartItemAddRequest(productId, 2));

        assertThat(existing.getQuantity()).isEqualTo(5);  // 3 + 2
        verify(cartItemRepository).save(existing);
    }

    @Test
    void addItem_throwsWhenCapExceeded() {
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(productClient.getProduct(productId)).thenReturn(snapshot);
        when(cartItemRepository.findByCartIdAndProductId(cartId, productId)).thenReturn(Optional.empty());

        // 100 > MAX_QUANTITY_PER_LINE (99)
        assertThatThrownBy(() -> service.addItem(userId, new CartItemAddRequest(productId, 100)))
            .isInstanceOf(BusinessException.class);
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    // ---------- updateItem ----------

    @Test
    void updateItem_zeroQuantityRemovesItem() {
        CartItem item = CartItem.builder()
            .id(cartItemId).cartId(cartId).productId(productId)
            .productTitle("Widget").unitPrice(new BigDecimal("9.99")).quantity(5)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(cartItemId)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartMapper.toResponse(cart, List.of())).thenReturn(emptyResponse());

        service.updateItem(userId, cartItemId, new CartItemUpdateRequest(0));

        verify(cartItemRepository).delete(item);
        verify(cartItemRepository, never()).save(item);
    }

    @Test
    void updateItem_throwsWhenCapExceeded() {
        CartItem item = CartItem.builder()
            .id(cartItemId).cartId(cartId).productId(productId)
            .productTitle("Widget").unitPrice(new BigDecimal("9.99")).quantity(5)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(cartItemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateItem(userId, cartItemId, new CartItemUpdateRequest(100)))
            .isInstanceOf(BusinessException.class);
        verify(cartItemRepository, never()).delete(any(CartItem.class));
    }

    // ---------- removeItem ----------

    @Test
    void removeItem_throwsOnCrossUser() {
        CartItem otherUsersItem = CartItem.builder()
            .id(cartItemId).cartId(otherCartId)  // belongs to a different cart
            .productId(productId).productTitle("Widget")
            .unitPrice(new BigDecimal("9.99")).quantity(1)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(cartItemId)).thenReturn(Optional.of(otherUsersItem));

        assertThatThrownBy(() -> service.removeItem(userId, cartItemId))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4006"));  // hide existence
        verify(cartItemRepository, never()).delete(any(CartItem.class));
    }

    @Test
    void removeItem_deletesOwnedItem() {
        CartItem item = CartItem.builder()
            .id(cartItemId).cartId(cartId).productId(productId)
            .productTitle("Widget").unitPrice(new BigDecimal("9.99")).quantity(1)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(cartItemId)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);

        service.removeItem(userId, cartItemId);

        verify(cartItemRepository).delete(item);
        verify(cartRepository).save(cart);
    }

    // ---------- clearCart ----------

    @Test
    void clearCart_marksCartDeleted() {
        CartItem item = CartItem.builder()
            .id(cartItemId).cartId(cartId).productId(productId)
            .productTitle("Widget").unitPrice(new BigDecimal("9.99")).quantity(1)
            .build();
        when(cartRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of(item));
        when(cartRepository.save(cart)).thenReturn(cart);

        service.clearCart(userId);

        verify(cartItemRepository).deleteAll(List.of(item));
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
    }
}
