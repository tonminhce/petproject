package com.shop.common.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaseSecurityConfigAudienceValidationTest {

    private static final String ISSUER = "https://keycloak.example/realms/shop";

    @Test
    void acceptsTokenForConfiguredServiceAudience() throws Exception {
        var result = validator(List.of("orders-service")).validate(jwt(List.of("orders-service", "account")));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenForDifferentServiceAudience() throws Exception {
        var result = validator(List.of("orders-service")).validate(jwt(List.of("products-service")));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsMissingAudienceWhenAudienceIsConfigured() throws Exception {
        var result = validator(List.of("orders-service")).validate(jwt(null));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsMalformedAudienceWithoutThrowing() throws Exception {
        var result = validator(List.of("orders-service")).validate(jwt("orders-service"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void preservesExplicitLegacyOptOutWhenNoAudienceConfigured() throws Exception {
        var result = validator(List.of()).validate(jwt(null));

        assertThat(result.hasErrors()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> validator(List<String> audiences) throws Exception {
        var props = new SecurityProperties(true, ISSUER, true, true, List.of(), audiences,
                new SecurityProperties.Cors(true, List.of("*"), List.of(), List.of("*"), List.of(), false, 3600L));
        Method method = BaseSecurityConfig.class.getDeclaredMethod("buildValidatorChain");
        method.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) method.invoke(new BaseSecurityConfig(props));
    }

    private Jwt jwt(Object audience) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer(ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            builder.claim("aud", audience);
        }
        return builder.build();
    }
}
