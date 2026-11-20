package com.shop.orderservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductServiceClientTest {

    @Test
    void theProductPriceCacheKeyIsVersionedSoEntriesWrittenBeforeTheCurrencyAreNeverReadBack() throws Exception {
        Cacheable cacheable = ProductServiceClient.class
                .getMethod("getProduct", UUID.class)
                .getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.value()).containsExactly("productPrice");
        assertThat(cacheable.key()).isNotEqualTo("#productId");

        UUID productId = UUID.randomUUID();
        ExpressionParser parser = new SpelExpressionParser();
        EvaluationContext context = new StandardEvaluationContext();
        context.setVariable("productId", productId);

        Object key = parser.parseExpression(cacheable.key()).getValue(context);

        assertThat(key).isEqualTo(productId.toString() + ":v2");
        assertThat(key).isNotEqualTo(productId.toString());
    }
}
