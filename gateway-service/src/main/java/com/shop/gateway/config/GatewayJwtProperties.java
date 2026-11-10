package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Gateway-side JWT validation settings.
 *
 * <p>F-040 — the provisioned Keycloak 26 realm has no audience mapper and no
 * client scopes, so the access tokens it mints carry NO {@code aud} claim; the
 * only client discriminator is {@code azp} (the client the token was issued
 * to). The reactive edge therefore validates {@code azp} against the
 * configured client ids instead of {@code aud}.</p>
 *
 * <p>F-032 — the gateway depends only on {@code common-core}, so
 * {@code common-security}'s servlet-only {@code BaseSecurityConfig} never
 * applies to it. Before this record the edge used Spring Boot's reactive
 * auto-configuration, whose default validator is issuer + timestamp only: a
 * token minted for ANY client in the realm was accepted at the edge.</p>
 *
 * @param expectedClientIds  client ids the edge accepts ({@code azp}); empty = no check
 * @param expectedAudiences  audience values the edge accepts ({@code aud}); empty = no check.
 *                           Only usable after an audience mapper is added to the realm.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.security")
public record GatewayJwtProperties(
        @DefaultValue List<String> expectedClientIds,
        @DefaultValue List<String> expectedAudiences
) {
}
