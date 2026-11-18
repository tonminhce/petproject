package com.shop.ratingservice.security;

import com.shop.ratingservice.config.RatingClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceTokenProviderCredentialTest {

    private static RatingClientProperties properties(String clientId, String clientSecret) {
        return new RatingClientProperties(
                new RatingClientProperties.OrderService("http://order.test", 3000),
                new RatingClientProperties.Keycloak("http://kc.test/token", clientId, clientSecret));
    }

    @Test
    void aConfiguredSecretIsAccepted() {
        assertThatCode(() -> new ServiceTokenProvider(RestClient.builder(), properties("rating-service", "s3cret")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretFailsTheStartupInsteadOfAuthenticatingWithAFallback() {
        assertThatThrownBy(() -> new ServiceTokenProvider(RestClient.builder(), properties("rating-service", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rating-service");
    }
}
