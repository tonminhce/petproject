package com.shop.orderservice.service.impls;

import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GuestCartCleanupScheduler {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @Value("${order.cleanup.guest-cart-days:14}")
    private int staleDays;

    @Scheduled(cron = "${order.cleanup.guest-cart-cron:0 0 4 * * *}")
    @Transactional
    public void cleanupStaleCarts() {
        try {
            Instant cutoff = Instant.now().minus(staleDays, ChronoUnit.DAYS);
            List<Cart> staleCarts = cartRepository.findStaleCarts(cutoff);
            if (staleCarts.isEmpty()) {
                return;
            }
            int itemCount = 0;
            for (Cart cart : staleCarts) {
                var items = cartItemRepository.findByCartId(cart.getId());
                itemCount += items.size();
                cartItemRepository.deleteAll(items);
            }
            cartRepository.deleteAll(staleCarts);
            log.info("Purged {} stale carts and {} orphaned cart items older than {} days",
                    staleCarts.size(), itemCount, staleDays);
        } catch (Exception ex) {
            log.error("Stale cart cleanup failed", ex);
        }
    }
}
