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

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration INVENTORY_TTL = Duration.ofSeconds(60);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(
            RedisConnectionFactory connectionFactory) {
        return builder -> builder
            .cacheWriter(RedisCacheWriter.create(connectionFactory, config -> config.immediateWrites()))
            .cacheDefaults(cacheConfig())
            .withCacheConfiguration("inventory", cacheConfig())
            .transactionAware();
    }

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
