package com.shop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for review finding C1: the {@code productPrice} cache
 * serializer must carry polymorphic type information. A serializer without
 * default typing writes no {@code @class} hint, so a cache HIT deserializes to
 * a {@code LinkedHashMap} and the {@code @Cacheable} proxy throws
 * {@code ClassCastException} on the second read of the same product.
 */
class CacheSerializerRoundTripTest {

    @Test
    void productSnapshot_roundTripsThroughRedisSerializer() {
        GenericJackson2JsonRedisSerializer serializer =
            CacheConfig.productPriceSerializer(new ObjectMapper());

        ProductSnapshot original =
            new ProductSnapshot(UUID.randomUUID(), "iPhone 15", new BigDecimal("999.00"));

        byte[] bytes = serializer.serialize(original);
        Object back = serializer.deserialize(bytes);

        assertThat(back).isInstanceOf(ProductSnapshot.class);
        assertThat((ProductSnapshot) back).isEqualTo(original);
    }

    @Test
    void serializedFormCarriesClassHint() {
        GenericJackson2JsonRedisSerializer serializer =
            CacheConfig.productPriceSerializer(new ObjectMapper());

        byte[] bytes = serializer.serialize(
            new ProductSnapshot(UUID.randomUUID(), "X", BigDecimal.ONE));

        // The @class hint is what makes polymorphic deserialization possible.
        assertThat(new String(bytes))
            .contains("@class")
            .contains("ProductSnapshot");
    }
}
