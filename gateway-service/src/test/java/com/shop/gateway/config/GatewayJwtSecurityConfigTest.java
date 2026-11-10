package com.shop.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayJwtSecurityConfigTest {

    private static final String ISSUER = "http://keycloak:8080/realms/ecommerce";

    @Test
    void tokenFromAnAcceptedClientPasses() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(
                new GatewayJwtProperties(List.of("ecommerce-client", "order-service"), List.of()));
        assertThat(hasErrors(validator, jwtWithAzp("ecommerce-client"))).isFalse();
    }

    @Test
    void tokenFromAnUnknownClientIsRejected() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(
                new GatewayJwtProperties(List.of("ecommerce-client"), List.of()));
        assertThat(hasErrors(validator, jwtWithAzp("some-other-client"))).isTrue();
    }

    @Test
    void issuerIsAlwaysEnforced() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(
                new GatewayJwtProperties(List.of("ecommerce-client"), List.of()));
        assertThat(hasErrors(validator, jwtWithAzp("ecommerce-client", "http://evil/realms/x"))).isTrue();
    }

    private boolean hasErrors(OAuth2TokenValidator<Jwt> validator, Jwt jwt) {
        var result = validator.validate(jwt);
        return result != null && result.hasErrors();
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> validator(GatewayJwtProperties props) throws Exception {
        Method method = GatewayJwtSecurityConfig.class.getDeclaredMethod("buildValidatorChain");
        method.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) method.invoke(new GatewayJwtSecurityConfig(props, ISSUER));
    }

    private Jwt jwtWithAzp(String azp) {
        return jwtWithAzp(azp, ISSUER);
    }

    private Jwt jwtWithAzp(String azp, String issuer) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer(issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (azp != null) {
            builder.claim("azp", azp);
        }
        return builder.build();
    }
}
