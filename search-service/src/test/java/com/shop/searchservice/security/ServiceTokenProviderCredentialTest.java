package com.shop.searchservice.security;

import com.shop.searchservice.config.ShopServicesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceTokenProviderCredentialTest {

    private static ShopServicesProperties properties(String clientId, String clientSecret) {
        return new ShopServicesProperties(
                new ShopServicesProperties.Service("http://product.test", 3000),
                new ShopServicesProperties.Keycloak("http://kc.test/token", clientId, clientSecret));
    }

    @Test
    void aConfiguredSecretIsAccepted() {
        assertThatCode(() -> new ServiceTokenProvider(RestClient.builder(), properties("search-service", "s3cret")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretFailsTheStartupInsteadOfAuthenticatingWithAFallback() {
        assertThatThrownBy(() -> new ServiceTokenProvider(RestClient.builder(), properties("search-service", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search-service");
    }
}
