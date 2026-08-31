package com.shop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration PRODUCT_PRICE_TTL = Duration.ofMinutes(10);

    /**
     * Builds the Redis cache manager with IMMEDIATE (synchronous) cache writes.
     *
     * <p>RACE this overrides (proven with Redis MONITOR wire evidence): spring-data-redis
     * 4.1.1's {@code DefaultRedisCacheWriter} defaults to {@code asynchronousWrites=true} —
     * {@code put()} dispatches the Redis {@code SET} fire-and-forget on a Netty/Reactor
     * event-loop thread via the reactive connection, while {@code get()} reads on the caller's
     * synchronous connection. A same-transaction (or same-request) reader that follows the
     * writer by only tens of microseconds races the not-yet-landed SET and misses, causing a
     * duplicate downstream fetch. There is no Boot {@code spring.cache.redis.*} property for
     * this — it must be set on the writer via {@code immediateWrites()}, which restores
     * synchronous put/clear semantics (writes block until visible).</p>
     *
     * <p>{@code .enableStatistics()} still applies on top of this writer (the builder wraps it
     * via {@code withStatisticsCollector}), keeping the order.cache.hit/miss gauges
     * (OrderMetrics) working. {@code .transactionAware()} is orthogonal: it defers to
     * after-commit only when transaction synchronization is active, which is never the case on
     * the read path in question.</p>
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(ObjectMapper objectMapper,
            RedisConnectionFactory connectionFactory) {
        var jacksonSerializer = productPriceSerializer(objectMapper);
        return builder -> builder
                // Immediate writes: async put default raced same-TX readers (duplicate
                // downstream fetch). No Boot property exists for this.
                .cacheWriter(RedisCacheWriter.create(connectionFactory, config -> config.immediateWrites()))
                // Required for the order.cache.hit/miss gauges (OrderMetrics) — without
                // it the cache writer uses a NoOp statistics collector and counters
                // stay at zero forever.
                .enableStatistics()
                .cacheDefaults(defaultConfig(jacksonSerializer))
                .withCacheConfiguration("productPrice", defaultConfig(jacksonSerializer).entryTtl(PRODUCT_PRICE_TTL))
                .transactionAware();  // no-arg — defense-in-depth
    }

    /**
     * The serializer MUST carry polymorphic type information: {@link ProductSnapshot}
     * is a record, and a serializer built without default typing writes no
     * {@code @class} hint — a cache HIT then deserializes to a {@code LinkedHashMap}
     * and the {@code @Cacheable} proxy throws {@code ClassCastException}
     * (review finding C1; regression-guarded by CacheSerializerRoundTripTest).
     *
     * <p>CRITICAL (re-review finding 1): {@code build()} MUTATES the mapper it
     * receives ({@code setDefaultTyping} + NullValue module). It must therefore
     * receive a PRIVATE copy — handing it the shared Boot bean would inject
     * {@code @class} into every MVC record payload and break request-body
     * deserialization service-wide. Guarded by
     * {@code CacheSerializerRoundTripTest.productPriceSerializer_doesNotMutateProvidedObjectMapper}.</p>
     */
    static GenericJackson2JsonRedisSerializer productPriceSerializer(ObjectMapper objectMapper) {
        return GenericJackson2JsonRedisSerializer.builder()
            .objectMapper(objectMapper.copy())
            .defaultTyping(true)
            .typeHintPropertyName("@class")
            .build();
    }

    private RedisCacheConfiguration defaultConfig(GenericJackson2JsonRedisSerializer serializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::")
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
