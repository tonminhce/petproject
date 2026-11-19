package com.shop.productservice.security;

import com.shop.productservice.config.MediaClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceTokenProviderCredentialTest {

    private static MediaClientProperties properties(String clientId, String clientSecret) {
        return new MediaClientProperties(
                "http://media.test",
                3000,
                new MediaClientProperties.Keycloak(
                        "http://kc.test/token", clientId, clientSecret));
    }

    @Test
    void aConfiguredSecretIsAccepted() {
        assertThatCode(() -> new ServiceTokenProvider(RestClient.builder(), properties("product-service", "s3cret")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretFailsTheStartupInsteadOfAuthenticatingWithAFallback() {
        assertThatThrownBy(() -> new ServiceTokenProvider(RestClient.builder(), properties("product-service", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product-service");
    }
}
