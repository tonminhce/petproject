package com.shop.productservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
 *
 * <p><b>Important:</b> Spring Boot 4 + Spring Data Redis 4.x defaults to
 * {@code JdkSerializationRedisSerializer}, which can't serialize records
 * ({@code ProductDetailResponse}, {@code ProductSummaryResponse}). We wire a
 * {@code GenericJackson2JsonRedisSerializer} with a hand-tuned ObjectMapper
 * (JavaTimeModule for {@code Instant}, polymorphic typing for record types) so
 * the same serializer survives cache round-trips for any cached type.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Product lookups are read-heavy and tolerate a short staleness window. */
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    /**
     * Categories/brands change rarely but are read on every product page and
     * filter facet; a longer TTL is safe because every write path evicts or
     * replaces the entry (see CategoryServiceImpl/BrandServiceImpl).
     */
    private static final Duration TAXONOMY_TTL = Duration.ofMinutes(30);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        // enableStatistics() is on RedisCacheManagerBuilder (Spring Data Redis
        // 4.x), not on the per-cache RedisCacheConfiguration. Wired here so
        // ProductMetrics can read hit/miss via RedisCache.getStatistics().
        return builder -> builder
                .enableStatistics()
                .withCacheConfiguration("product", cacheConfig(PRODUCT_TTL))
                .withCacheConfiguration("productBySlug", cacheConfig(PRODUCT_TTL))
                .withCacheConfiguration("category", cacheConfig(TAXONOMY_TTL))
                .withCacheConfiguration("brand", cacheConfig(TAXONOMY_TTL));
    }

    /**
     * Base cache configuration: fixed TTL, JSON value serializer, no null
     * caching (a miss must stay a miss so deletes are not masked), and
     * {@code <cacheName>::<key>} key layout.
     */
    private RedisCacheConfiguration cacheConfig(Duration ttl) {
        // Build the ObjectMapper with JavaTimeModule so Instant / LocalDateTime
        // round-trip cleanly. We then hand it to the serializer via the builder,
        // which also enables the default-typing writer that emits a @class
        // hint on every serialized value — without it, the read path
        // can't reconstruct the original record type.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        GenericJackson2JsonRedisSerializer serializer = GenericJackson2JsonRedisSerializer.builder()
                .objectMapper(mapper)
                .defaultTyping(true)
                .typeHintPropertyName("@class")
                .build();
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(name -> name + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
