package com.shop.common.security.service;

import com.shop.common.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCallerGuardFailClosedTest {

    private final ServiceCallerGuard guard = new ServiceCallerGuard();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptySecurityContextIsRejected() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0401"));
    }

    @Test
    void authenticationWithoutPrincipalIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalLessAuthentication());

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0401"));
    }

    @Test
    void unauthenticatedAuthenticationIsRejected() {
        Authentication unauthenticated = new TestingAuthenticationToken("order-service", "n/a");
        unauthenticated.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0401"));
    }

    @Test
    void anonymousAuthenticationIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0401"));
    }

    @Test
    void allowedClientPasses() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("order-service"), List.of()));

        assertThatCode(() -> guard.requireCaller("order-service")).doesNotThrowAnyException();
    }

    @Test
    void clientOutsideTheAllowListIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("media-service"), List.of()));

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0403"));
    }

    @Test
    void tokenWithoutClientIdentityIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(null), List.of()));

        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ERR-0403"));
    }

    @Test
    void humanAdminBypassesTheClientCheck() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(null),
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThatCode(() -> guard.requireCaller("order-service")).doesNotThrowAnyException();
    }

    private Jwt jwt(String authorizedParty) {
        Jwt.Builder builder = Jwt.withTokenValue("guard-token")
                .header("alg", "none")
                .subject("00000000-0000-0000-0000-00000000a001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (authorizedParty != null) {
            builder.claim("azp", authorizedParty);
        }
        return builder.build();
    }

    private static final class PrincipalLessAuthentication implements Authentication {

        @Override
        public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return null;
        }

        @Override
        public boolean isAuthenticated() {
            return true;
        }

        @Override
        public void setAuthenticated(boolean isAuthenticated) {
        }

        @Override
        public java.util.Map<String, Object> getDetails() {
            return java.util.Map.of();
        }

        @Override
        public String getName() {
            return "principal-less";
        }
    }
}
