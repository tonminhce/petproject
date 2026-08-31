package com.shop.inventoryservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
 * {@code InventoryCacheService.evictAfterCommit(productId)} called by every write path.</p>
 *
 * <p><b>Immediate writes (fleet rule 6):</b> spring-data-redis 4.1.1's
 * {@code DefaultRedisCacheWriter} defaults to {@code asynchronousWrites=true} —
 * {@code put()} dispatches the Redis {@code SET} fire-and-forget on a
 * Netty/Reactor event-loop thread while {@code get()} reads on the caller's
 * synchronous connection, so a same-request reader racing the writer by tens of
 * microseconds misses and re-fetches downstream (proven with Redis MONITOR wire
 * evidence in order-service; fixed there in commit 02ac753). No Boot
 * {@code spring.cache.redis.*} property exists for this — it must be set on the
 * writer via {@code immediateWrites()}, which also makes {@code clear()}
 * synchronous (see the IT-base per-test cache reset). Orthogonal to
 * {@code transactionAware()}, which only defers to after-commit when transaction
 * synchronization is active.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration INVENTORY_TTL = Duration.ofSeconds(60);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(
            RedisConnectionFactory connectionFactory) {
        return builder -> builder
            // Immediate writes: async put default raced same-request readers
            // (fleet rule 6). No Boot property exists for this.
            .cacheWriter(RedisCacheWriter.create(connectionFactory, config -> config.immediateWrites()))
            .cacheDefaults(cacheConfig())
            .withCacheConfiguration("inventory", cacheConfig())
            .transactionAware();   // NO-ARG ONLY — defer evict to after-commit
    }

    /**
     * Base cache configuration: fixed TTL, JSON value serializer, no null
     * caching (a miss must stay a miss so deletes are not masked), and
     * {@code <cacheName>::<key>} key layout.
     *
     * <p>The JSON serializer is REQUIRED, not optional: Spring Boot 4 +
     * Spring Data Redis 4.x default to {@code JdkSerializationRedisSerializer},
     * which cannot round-trip the {@link InventoryResponse} record (and would
     * fail on its {@code Instant} field even if it could). Mirrors the setup
     * in product-service's CacheConfig.</p>
     */
    private RedisCacheConfiguration cacheConfig() {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        GenericJackson2JsonRedisSerializer serializer = GenericJackson2JsonRedisSerializer.builder()
            .objectMapper(mapper)
            .defaultTyping(true)
            .typeHintPropertyName("@class")
            .build();
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(INVENTORY_TTL)
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::")
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
