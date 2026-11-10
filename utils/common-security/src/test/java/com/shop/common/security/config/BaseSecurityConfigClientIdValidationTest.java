package com.shop.common.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-040 — client-identity validation via the {@code azp} claim.
 *
 * <p>Runtime evidence against the provisioned Keycloak 26 realm (production
 * audit): the realm has no audience mapper and no client scopes, so every
 * access token it mints carries NO {@code aud} claim - only {@code azp}, the
 * client the token was issued to:</p>
 *
 * <pre>
 *   user token     -> azp=ecommerce-client, aud absent
 *   admin token    -> azp=ecommerce-client, aud absent
 *   service token  -> azp=order-service,   aud absent
 * </pre>
 *
 * <p>The validator therefore reads {@code azp}. A token minted for a client the
 * service does not accept must be rejected.</p>
 */
class BaseSecurityConfigClientIdValidationTest {

    private static final String ISSUER = "http://keycloak:8080/realms/ecommerce";

    @Test
    void tokenFromAnAcceptedClientPasses() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(List.of("ecommerce-client", "order-service"));
        assertThat(hasErrors(validator, jwtWithAzp("ecommerce-client"))).isFalse();
    }

    @Test
    void tokenFromAnUnknownClientIsRejected() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(List.of("ecommerce-client"));
        assertThat(hasErrors(validator, jwtWithAzp("some-other-client"))).isTrue();
    }

    @Test
    void tokenWithoutAzpIsRejectedWhenTheCheckIsEnabled() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(List.of("ecommerce-client"));
        assertThat(hasErrors(validator, jwtWithAzp(null))).isTrue();
    }

    @Test
    void noClientCheckConfiguredMeansNoAzpValidator() throws Exception {
        OAuth2TokenValidator<Jwt> validator = validator(List.of());
        // The claim is absent, so only an azp validator could reject it.
        assertThat(hasErrors(validator, jwtWithAzp(null))).isFalse();
    }

    private boolean hasErrors(OAuth2TokenValidator<Jwt> validator, Jwt jwt) {
        var result = validator.validate(jwt);
        return result != null && result.hasErrors();
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> validator(List<String> clientIds) throws Exception {
        var props = new SecurityProperties(true, ISSUER, true, true, List.of(), List.of(), clientIds,
                new SecurityProperties.Cors(true, List.of("*"), List.of(), List.of("*"), List.of(), false, 3600L));
        Method method = BaseSecurityConfig.class.getDeclaredMethod("buildValidatorChain");
        method.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) method.invoke(new BaseSecurityConfig(props));
    }

    private Jwt jwtWithAzp(String azp) {
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer(ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (azp != null) {
            builder.claim("azp", azp);
        }
        return builder.build();
    }
}
