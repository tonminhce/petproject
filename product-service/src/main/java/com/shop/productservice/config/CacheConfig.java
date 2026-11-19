package com.shop.productservice.config;

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

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    private static final Duration TAXONOMY_TTL = Duration.ofMinutes(30);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer(
            RedisConnectionFactory connectionFactory) {
        return builder -> builder
                .cacheWriter(RedisCacheWriter.create(connectionFactory, config -> config.immediateWrites()))
                .enableStatistics()
                .withCacheConfiguration("product", cacheConfig(PRODUCT_TTL))
                .withCacheConfiguration("productBySlug", cacheConfig(PRODUCT_TTL))
                .withCacheConfiguration("category", cacheConfig(TAXONOMY_TTL))
                .withCacheConfiguration("brand", cacheConfig(TAXONOMY_TTL));
    }

    private RedisCacheConfiguration cacheConfig(Duration ttl) {

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
