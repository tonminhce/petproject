package com.shop.mediaservice.security;

import com.shop.mediaservice.config.ProductClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceTokenProviderCredentialTest {

    private static ProductClientProperties properties(String clientId, String clientSecret) {
        return new ProductClientProperties(
                "http://product.test",
                3000,
                new ProductClientProperties.Keycloak(
                        "http://kc.test/token", clientId, clientSecret));
    }

    @Test
    void aConfiguredSecretIsAccepted() {
        assertThatCode(() -> new ServiceTokenProvider(RestClient.builder(), properties("media-service", "s3cret")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretFailsTheStartupInsteadOfAuthenticatingWithAFallback() {
        assertThatThrownBy(() -> new ServiceTokenProvider(RestClient.builder(), properties("media-service", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media-service");
    }
}
