package com.shop.common.logging.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6 binding: actor is JWT {@code sub} (user) or {@code clientId}/{@code azp}
 * (service) — email/phone/name are never read as the identifier, even when
 * present on the token.
 */
class AuditActorResolverTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userTokenResolvesToSubject() {
        login(Map.of(
                "sub", "3f6d2f4e-8e7a-4a1b-9c0d-123456789abc",
                "email", "admin@shop.com",
                "name", "Admin User",
                "preferred_username", "admin"));

        AuditActorResolver.Actor actor = AuditActorResolver.resolve();

        assertThat(actor.id()).isEqualTo("3f6d2f4e-8e7a-4a1b-9c0d-123456789abc");
        assertThat(actor.type()).isEqualTo(AuditEvent.ACTOR_TYPE_USER);
    }

    @Test
    void serviceTokenWithClientIdResolvesToClientIdEvenWhenSubPresent() {
        login(Map.of(
                "sub", "service-account-uuid",
                "clientId", "order-service",
                "azp", "order-service"));

        AuditActorResolver.Actor actor = AuditActorResolver.resolve();

        assertThat(actor.id()).isEqualTo("order-service");
        assertThat(actor.type()).isEqualTo(AuditEvent.ACTOR_TYPE_SERVICE);
    }

    @Test
    void serviceTokenWithOnlyAzpResolvesToAuthorizedParty() {
        login(Map.of("azp", "rating-service"));

        AuditActorResolver.Actor actor = AuditActorResolver.resolve();

        assertThat(actor.id()).isEqualTo("rating-service");
        assertThat(actor.type()).isEqualTo(AuditEvent.ACTOR_TYPE_SERVICE);
    }

    @Test
    void noAuthenticationResolvesToAnonymous() {
        SecurityContextHolder.clearContext();

        AuditActorResolver.Actor actor = AuditActorResolver.resolve();

        assertThat(actor.id()).isEqualTo("anonymous");
        assertThat(actor.type()).isEqualTo(AuditEvent.ACTOR_TYPE_USER);
    }

    @Test
    void nonJwtPrincipalResolvesToAnonymous() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("basic-user", "n/a", "ROLE_ADMIN"));

        AuditActorResolver.Actor actor = AuditActorResolver.resolve();

        assertThat(actor.id()).isEqualTo("anonymous");
    }

    private static void login(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claims(map -> map.putAll(claims))
                .build();
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(jwt, "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
