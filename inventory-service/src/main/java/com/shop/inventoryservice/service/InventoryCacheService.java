package com.shop.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryCacheService {

    private static final String CACHE_NAME = "inventory";

    private final CacheManager cacheManager;

    public void evict(UUID productId) {
        evictQuietly(productId);
    }

    public void evictAfterCommit(UUID productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictQuietly(productId);
                }
            });
        } else {

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
            log.warn("Failed to evict inventory cache for productId {}: {}", productId, ex.getMessage());
        }
    }
}
