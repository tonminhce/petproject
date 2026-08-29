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
            .cacheDefaults(defaultConfig(jacksonSerializer))
            .withCacheConfiguration("productPrice", defaultConfig(jacksonSerializer).entryTtl(PRODUCT_PRICE_TTL))
            .transactionAware();  // no-arg — defense-in-depth
    }

    /**
     * The serializer MUST carry polymorphic type information: {@link ProductSnapshot}
     * is a record, and a serializer built from a plain {@code new GenericJackson2JsonRedisSerializer(objectMapper)}
     * writes no {@code @class} hint — a cache HIT then deserializes to a
     * {@code LinkedHashMap} and the {@code @Cacheable} proxy throws
     * {@code ClassCastException} (review finding C1, reproduced by probe;
     * regression-guarded by CacheSerializerRoundTripTest). Mirrors the
     * product-service CacheConfig builder pattern.
     */
    static GenericJackson2JsonRedisSerializer productPriceSerializer(ObjectMapper objectMapper) {
        return GenericJackson2JsonRedisSerializer.builder()
            .objectMapper(objectMapper)
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
