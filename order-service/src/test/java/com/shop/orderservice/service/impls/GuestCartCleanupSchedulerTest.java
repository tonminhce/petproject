package com.shop.orderservice.service.impls;

import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuestCartCleanupSchedulerTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    private GuestCartCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GuestCartCleanupScheduler(cartRepository, cartItemRepository);
        ReflectionTestUtils.setField(scheduler, "staleDays", 14);
    }

    @Test
    void cleanupStaleCarts_noStaleCarts_doesNothing() {
        when(cartRepository.findStaleCarts(any(Instant.class))).thenReturn(List.of());

        scheduler.cleanupStaleCarts();

        verify(cartItemRepository, never()).deleteAll(any());
        verify(cartRepository, never()).deleteAll(any());
    }

    @Test
    void cleanupStaleCarts_purgesCartsAndItems() {
        UUID cartId = UUID.randomUUID();
        Cart cart = Cart.builder().id(cartId).userId(UUID.randomUUID()).build();
        CartItem item = CartItem.builder().id(UUID.randomUUID()).cartId(cartId).build();

        when(cartRepository.findStaleCarts(any(Instant.class))).thenReturn(List.of(cart));
        when(cartItemRepository.findByCartId(cartId)).thenReturn(List.of(item));

        scheduler.cleanupStaleCarts();

        verify(cartItemRepository).deleteAll(List.of(item));
        verify(cartRepository).deleteAll(List.of(cart));
    }
}
