package com.shop.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Cache invalidation helper for the inventory cache.
 *
 * <p>{@code evictAfterCommit} registers a synchronization so the Redis key is
 * removed ONLY after the current transaction commits successfully. If the
 * transaction rolls back, the cache is NOT touched - preventing a spurious
 * miss and, worse, a stale write-back between evict and commit.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryCacheService {

    private static final String CACHE_NAME = "inventory";

    private final CacheManager cacheManager;

    /** Evict the cache entry for a productId immediately (outside a transaction). */
    public void evict(UUID productId) {
        evictQuietly(productId);
    }

    /** Evict AFTER commit - safe to call inside a @Transactional method. */
    public void evictAfterCommit(UUID productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictQuietly(productId);
                }
            });
        } else {
            // No active transaction - evict immediately.
            evictQuietly(productId);
        }
    }

    private void evictQuietly(UUID productId) {
        try {
            var cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.evict(productId);
            }
        } catch (Exception ex) {
            // Redis failure - log and let TTL expire the stale entry (eventual consistency).
            log.warn("Failed to evict inventory cache for productId {}: {}", productId, ex.getMessage());
        }
    }
}
