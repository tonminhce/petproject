package com.shop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        var jacksonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        return builder -> builder
            .cacheDefaults(defaultConfig(jacksonSerializer))
            .withCacheConfiguration("productPrice", defaultConfig(jacksonSerializer).entryTtl(PRODUCT_PRICE_TTL))
            .transactionAware();  // ⚠️ no-arg — defense-in-depth
    }

    private RedisCacheConfiguration defaultConfig(GenericJackson2JsonRedisSerializer serializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::")
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
