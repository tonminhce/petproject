package com.shop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration PRODUCT_PRICE_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(ObjectMapper objectMapper) {
        var jacksonSerializer = productPriceSerializer(objectMapper);
        return builder -> builder
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
