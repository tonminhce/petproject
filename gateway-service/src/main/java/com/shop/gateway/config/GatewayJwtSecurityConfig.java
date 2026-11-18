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

    static OAuth2TokenValidator<Jwt> validatorFor(GatewayJwtProperties properties, String issuerUri) {
        GatewayJwtSecurityConfig config = new GatewayJwtSecurityConfig(properties, issuerUri);
        return config.buildValidatorChain();
    }
}
