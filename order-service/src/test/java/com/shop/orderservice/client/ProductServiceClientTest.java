package com.shop.orderservice.client;

import com.shop.orderservice.exception.ProductLookupRejectedException;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

    @Test
    void anUnknownProductSurfacesAsAProductLookupRejectionAndNotAGenericFailure() {
        UUID productId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProductServiceClient client = new ProductServiceClient(builder.build());
        server.expect(requestTo("http://product.test/api/v1/products/" + productId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getProduct(productId))
            .isInstanceOfSatisfying(ProductLookupRejectedException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRD-2001"));

        server.verify();
    }
}
