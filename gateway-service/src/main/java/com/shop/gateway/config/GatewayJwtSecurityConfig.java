package com.shop.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.util.ArrayList;
import java.util.List;

/**
 * F-032 — the gateway is the edge, so it is the place where a token minted for
 * the wrong client must be rejected. Before this configuration the gateway
 * relied on Spring Boot's reactive auto-configuration, whose default validator
 * chain is issuer + timestamp only:
 *
 * <ul>
 *   <li>the gateway depends only on {@code common-core}, not {@code common-security},
 *       so the servlet-only {@code BaseSecurityConfig} (issuer + timestamp +
 *       audience) never applied to it;</li>
 *   <li>no {@code JwtDecoder} bean existed in the gateway, so Boot's
 *       {@code ReactiveJwtDecoder} was used and the audience check was absent
 *       entirely. A token issued to any client in the realm was accepted at the
 *       edge.</li>
 * </ul>
 *
 * <p>F-040 — the validator reads the {@code azp} claim, because the
 * provisioned realm emits no {@code aud} claim at all (measured against
 * Keycloak 26: user tokens carry {@code azp=ecommerce-client}, service tokens
 * carry {@code azp=<service-client>}, and {@code aud} is absent for every
 * token type). An {@code aud} validator is still wired when the operator
 * configures it, so the control becomes usable as soon as an audience mapper
 * is added to the realm.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GatewayJwtSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayJwtSecurityConfig.class);

    private final GatewayJwtProperties properties;
    private final String issuerUri;

    public GatewayJwtSecurityConfig(
            GatewayJwtProperties properties,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        this.properties = properties;
        this.issuerUri = issuerUri;
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        OAuth2TokenValidator<Jwt> chain = buildValidatorChain();
        // Lazy: the discovery fetch is deferred to the first decode so the
        // edge boots even when the IdP is briefly unavailable.
        return new LazyReactiveJwtDecoder(() -> {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
            decoder.setJwtValidator(chain);
            return decoder;
        });
    }

    private OAuth2TokenValidator<Jwt> buildValidatorChain() {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtIssuerValidator(issuerUri));
        validators.add(new JwtTimestampValidator());

        if (!properties.expectedAudiences().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("aud", aud -> {
                if (!(aud instanceof List<?> audiences) || audiences.isEmpty()) {
                    return false;
                }
                return audiences.stream().allMatch(String.class::isInstance)
                        && properties.expectedAudiences().stream().anyMatch(audiences::contains);
            }));
            log.info("Gateway JWT audience validation enabled for {}", properties.expectedAudiences());
        }

        if (!properties.expectedClientIds().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("azp", azp -> {
                if (!(azp instanceof String clientId) || clientId.isBlank()) {
                    return false;
                }
                return properties.expectedClientIds().contains(clientId);
            }));
            log.info("Gateway JWT client (azp) validation enabled for {}", properties.expectedClientIds());
        } else {
            log.warn("Gateway JWT client validation is DISABLED - a token minted for any client "
                    + "in the realm is accepted at the edge. Set gateway.security.expected-client-ids.");
        }

        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Narrow helper so the test suite can assert the chain without a live IdP.
     */
    static OAuth2TokenValidator<Jwt> validatorFor(GatewayJwtProperties properties, String issuerUri) {
        GatewayJwtSecurityConfig config = new GatewayJwtSecurityConfig(properties, issuerUri);
        return config.buildValidatorChain();
    }
}
