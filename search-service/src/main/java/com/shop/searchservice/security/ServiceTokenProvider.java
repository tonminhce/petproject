package com.shop.searchservice.security;

import com.shop.common.keycloak.client.KeycloakServiceTokenProvider;
import com.shop.searchservice.config.ShopServicesProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ServiceTokenProvider {

    private final KeycloakServiceTokenProvider delegate;

    public ServiceTokenProvider(RestClient.Builder restClientBuilder,
                                ShopServicesProperties properties) {
        ShopServicesProperties.Keycloak kc = properties.keycloak();
        this.delegate = new KeycloakServiceTokenProvider(
                restClientBuilder,
                kc.tokenUrl(),
                kc.clientId(),
                kc.clientSecret());
    }

    public String getToken() {
        return delegate.getToken();
    }
}
