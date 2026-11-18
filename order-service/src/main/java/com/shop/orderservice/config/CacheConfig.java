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

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(ObjectMapper objectMapper,
            RedisConnectionFactory connectionFactory) {
        var jacksonSerializer = productPriceSerializer(objectMapper);
        return builder -> builder
                .cacheWriter(RedisCacheWriter.create(connectionFactory, config -> config.immediateWrites()))
                .enableStatistics()
                .cacheDefaults(defaultConfig(jacksonSerializer))
                .withCacheConfiguration("productPrice", defaultConfig(jacksonSerializer).entryTtl(PRODUCT_PRICE_TTL))
                .transactionAware();
    }

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
