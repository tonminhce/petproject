package com.shop.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CacheSerializerRoundTripTest {

    @Test
    void productSnapshot_roundTripsThroughRedisSerializer() {
        GenericJackson2JsonRedisSerializer serializer =
            CacheConfig.productPriceSerializer(new ObjectMapper());

        ProductSnapshot original =
            new ProductSnapshot(UUID.randomUUID(), "iPhone 15", new BigDecimal("999.00"), "VND");

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
            new ProductSnapshot(UUID.randomUUID(), "X", BigDecimal.ONE, "VND"));

        assertThat(new String(bytes))
            .contains("@class")
            .contains("ProductSnapshot");
    }

    @Test
    void productPriceSerializer_doesNotMutateProvidedObjectMapper() throws Exception {
        ObjectMapper shared = new ObjectMapper();

        CacheConfig.productPriceSerializer(shared);

        byte[] appJson = shared.writeValueAsBytes(
            new ProductSnapshot(UUID.randomUUID(), "App", BigDecimal.ONE, "VND"));
        assertThat(new String(appJson)).doesNotContain("@class");
        assertThat(shared.readValue(appJson, ProductSnapshot.class).title()).isEqualTo("App");
    }
}
