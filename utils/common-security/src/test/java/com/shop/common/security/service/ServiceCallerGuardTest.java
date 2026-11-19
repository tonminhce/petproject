package com.shop.common.security.service;

import com.shop.common.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCallerGuardTest {

    private final ServiceCallerGuard guard = new ServiceCallerGuard();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theAllowedServiceClientMayCall() {
        SecurityContextHolder.getContext().setAuthentication(jwt("order-service", List.of("ROLE_SERVICE")));

        assertThatCode(() -> guard.requireCaller("order-service")).doesNotThrowAnyException();
    }

    @Test
    void anotherServiceClientIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(jwt("search-service", List.of("ROLE_SERVICE")));

        assertForbidden(guard);
    }

    @Test
    void aHumanAdminMayCall() {
        SecurityContextHolder.getContext().setAuthentication(jwt("ecommerce-client", List.of("ROLE_ADMIN")));

        assertThatCode(() -> guard.requireCaller("order-service")).doesNotThrowAnyException();
    }

    @Test
    void aHumanManagerMayCall() {
        SecurityContextHolder.getContext().setAuthentication(jwt("ecommerce-client", List.of("ROLE_MANAGER")));

        assertThatCode(() -> guard.requireCaller("order-service")).doesNotThrowAnyException();
    }

    @Test
    void aTokenWithoutAClientIdentityIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(jwt(null, List.of("ROLE_SERVICE")));

        assertForbidden(guard);
    }

    @Test
    void anAnonymousCallerIsRejected() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        assertForbidden(guard);
    }

    @Test
    void anAbsentAuthenticationIsRejectedInsteadOfSilentlyAllowed() {
        SecurityContextHolder.clearContext();

        assertForbidden(guard);
    }

    private void assertForbidden(ServiceCallerGuard guard) {
        assertThatThrownBy(() -> guard.requireCaller("order-service"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getErrorCode()).isEqualTo("ERR-0403");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    private JwtAuthenticationToken jwt(String clientId, List<String> authorities) {
        Map<String, Object> claims = clientId == null ? Map.of() : Map.of("azp", clientId);
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        Jwt jwt = builder.build();
        return new JwtAuthenticationToken(jwt, authorities.stream().map(SimpleGrantedAuthority::new).toList());
    }
}
