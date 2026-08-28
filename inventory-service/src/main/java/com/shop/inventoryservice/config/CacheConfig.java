package com.shop.inventoryservice.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Redis cache tuning for inventory read paths.
 *
 * <p>The {@code RedisCacheManager} itself is built by Spring Boot cache autoconfig
 * ({@code spring.cache.type: redis}); this class only customises the per-cache entries.
 * Boot 4 package: {@code RedisCacheManagerBuilderCustomizer} lives in
 * {@code org.springframework.boot.cache.autoconfigure}.</p>
 *
 * <p>{@code transactionAware()} (NO-ARG — {@code transactionAware(boolean)} does NOT
 * exist on the builder, verified against spring-data-redis 4.1.1) defers any
 * {@code @CacheEvict} to after-commit. Primary invalidation is the manual
 * {@code InventoryCacheService.evictAfterCommit(productId)} called by every write path.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration INVENTORY_TTL = Duration.ofSeconds(60);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .cacheDefaults(cacheConfig())
            .withCacheConfiguration("inventory", cacheConfig())
            .transactionAware();   // NO-ARG ONLY — defer evict to after-commit
    }

    private RedisCacheConfiguration cacheConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(INVENTORY_TTL)
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::");
    }
}
