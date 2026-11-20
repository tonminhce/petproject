package com.shop.common.spring.security;

import com.shop.common.security.config.BaseSecurityConfig;
import com.shop.common.spring.autoconfigure.AudienceAutoConfiguration;
import com.shop.common.security.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceBoundaryTest {

    private static final String ISSUER = "http://keycloak:8080/realms/ecommerce";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AudienceAutoConfiguration.class));

    @Test
    @DisplayName("the per-service audience is published for the security validator chain")
    void thePerServiceAudienceIsPublishedForTheSecurityValidatorChain() {
        runner.withPropertyValues(
                        "spring.application.name=order-service",
                        "shop.security.issuer-uri=" + ISSUER,
                        "shop.security.expected-client-ids=ecommerce-client",
                        "shop.security.audience.client-id=order-service",
                        "shop.security.audience.per-service-client-ids.order-service[0]=ecommerce-client",
                        "shop.security.audience.per-service-client-ids.order-service[1]=rating-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("shop.security.expected-client-ids"))
                            .isEqualTo("ecommerce-client,rating-service");

                    SecurityProperties properties = Binder.get(context.getEnvironment())
                            .bind("shop.security", SecurityProperties.class)
                            .get();
                    assertThat(properties.expectedClientIds())
                            .containsExactly("ecommerce-client", "rating-service");
                });
    }

    @Test
    @DisplayName("an empty expected client id list is a startup failure")
    void anEmptyExpectedClientIdListIsAStartupFailure() {
        runner.withPropertyValues(
                        "shop.security.issuer-uri=" + ISSUER,
                        "shop.security.expected-client-ids=",
                        "shop.security.audience.client-id=order-service")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("shop.security.expected-client-ids must not be empty");
                });
    }

    @Test
    @DisplayName("an empty expected client id list is rejected by the configuration binding")
    void anEmptyExpectedClientIdListIsRejectedByTheConfigurationBinding() {
        runner.withPropertyValues(
                        "shop.security.issuer-uri=" + ISSUER,
                        "shop.security.expected-client-ids=",
                        "shop.security.audience.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("expectedClientIds")
                            .hasStackTraceContaining("must not be empty");
                });
    }

    @Test
    @DisplayName("an explicit operator list overrides the per-service audience")
    void anExplicitOperatorListOverridesThePerServiceAudience() {
        runner.withPropertyValues(
                        "shop.security.issuer-uri=" + ISSUER,
                        "shop.security.expected-client-ids=ecommerce-client,order-service",
                        "shop.security.audience.client-id=order-service",
                        "shop.security.audience.per-service-client-ids.order-service[0]=ecommerce-client")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("shop.security.expected-client-ids"))
                            .isEqualTo("ecommerce-client,order-service");
                });
    }

    @Test
    @DisplayName("a token minted for another client is rejected by the security validator chain")
    void aTokenMintedForAnotherClientIsRejected() throws Exception {
        SecurityProperties properties = new SecurityProperties(
                true, ISSUER, true, true, List.of(), List.of(), List.of("ecommerce-client"), null);
        OAuth2TokenValidator<Jwt> validator = validatorChain(properties);

        Jwt foreignClient = tokenWithClientId("media-service");
        Jwt ownClient = tokenWithClientId("ecommerce-client");

        assertThat(validator.validate(foreignClient).hasErrors())
                .as("a token minted for another service client must be rejected")
                .isTrue();
        assertThat(validator.validate(ownClient).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("a token with an unexpected issuer is rejected")
    void aTokenWithAnUnexpectedIssuerIsRejected() throws Exception {
        SecurityProperties properties = new SecurityProperties(
                true, ISSUER, true, true, List.of(), List.of(), List.of("ecommerce-client"), null);
        OAuth2TokenValidator<Jwt> validator = validatorChain(properties);

        Jwt foreignIssuer = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "http://evil:8080/realms/ecommerce")
                .claim("azp", "ecommerce-client")
                .build();

        assertThat(validator.validate(foreignIssuer).hasErrors())
                .as("a token from an unexpected issuer must be rejected")
                .isTrue();
    }

    @Test
    @DisplayName("a token with an unexpected audience is rejected")
    void aTokenWithAnUnexpectedAudienceIsRejected() throws Exception {
        SecurityProperties properties = new SecurityProperties(
                true, ISSUER, true, true, List.of(), List.of("order-service"), List.of("ecommerce-client"), null);
        OAuth2TokenValidator<Jwt> validator = validatorChain(properties);

        Jwt foreignAudience = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .claim("aud", List.of("media-service"))
                .claim("azp", "ecommerce-client")
                .build();

        assertThat(validator.validate(foreignAudience).hasErrors())
                .as("a token with an unexpected audience must be rejected")
                .isTrue();
    }

    private static OAuth2TokenValidator<Jwt> validatorChain(SecurityProperties properties) throws Exception {
        Method method = BaseSecurityConfig.class.getDeclaredMethod("buildValidatorChain");
        method.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) method.invoke(new BaseSecurityConfig(properties));
    }

    private static Jwt tokenWithClientId(String clientId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", ISSUER)
                .claim("azp", clientId)
                .build();
    }
}