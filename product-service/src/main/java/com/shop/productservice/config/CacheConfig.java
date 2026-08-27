package com.shop.productservice.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Redis cache tuning for the catalog read paths.
 *
 * <p>The {@code RedisCacheManager} itself is built by Spring Boot's cache
 * autoconfiguration ({@code spring.cache.type: redis}); this class only
 * customises the per-cache entries so each one can carry its own TTL instead of
 * inheriting the global {@code spring.cache.redis.time-to-live} fallback.</p>
 *
 * <p>Note the Boot 4 package: {@code RedisCacheManagerBuilderCustomizer} lives in
 * {@code org.springframework.boot.cache.autoconfigure} (module
 * {@code spring-boot-cache}), no longer in {@code o.s.b.autoconfigure.cache}.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Product lookups are read-heavy and tolerate a short staleness window. */
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        // enableStatistics() is on RedisCacheManagerBuilder (Spring Data Redis
        // 4.x), not on the per-cache RedisCacheConfiguration. Wired here so
        // ProductMetrics can read hit/miss via RedisCache.getStatistics().
        return builder -> builder
                .enableStatistics()
                .withCacheConfiguration("product", cacheConfig(PRODUCT_TTL))
                .withCacheConfiguration("productBySlug", cacheConfig(PRODUCT_TTL));
    }

    /**
     * Base cache configuration: fixed TTL, no null caching (a miss must stay a
     * miss so deletes are not masked), and {@code <cacheName>::<key>} key layout.
     */
    private RedisCacheConfiguration cacheConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(name -> name + "::");
    }
}
