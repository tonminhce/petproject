package com.shop.orderservice.service.impls;

import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.service.CartService;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CartClearLockIT extends AbstractOrderServiceIT {

    @Autowired
    private CartService cartService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theClearOfTheCartWaitsForTheWriteLockOfTheCart() throws Exception {
        UUID userId = UUID.randomUUID();
        cartRepository.saveAndFlush(Cart.builder()
                .userId(userId)
                .subtotal(BigDecimal.ZERO)
                .build());

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> template.execute(status -> {
                cartRepository.findByUserIdForUpdate(userId);
                locked.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();

            Future<?> clearer = pool.submit(() -> cartService.clearCart(userId));
            Thread.sleep(800);
            assertThat(clearer.isDone())
                    .as("the clear of the cart must wait for the write lock of the cart")
                    .isFalse();

            release.countDown();
            clearer.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
