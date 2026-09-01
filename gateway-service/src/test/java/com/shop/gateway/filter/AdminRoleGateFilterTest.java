package com.shop.gateway.filter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRoleGateFilterTest {

    private final AdminRoleGateFilter filter = new AdminRoleGateFilter(
            new GatewayErrorResponseWriter(new ObjectMapper()));

    @Test
    void nonBackofficePathPassesWithoutRoleCheck() {
        var exchange = exchange("/api/v1/products");

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(userAuth()))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void realmAdminRolePasses() {
        var exchange = exchange("/api/v1/backoffice/ratings");

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authWithRoles(List.of("USER", "ADMIN"))))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void authenticatedWithoutAdminGets403Envelope() throws Exception {
        var exchange = exchange("/api/v1/backoffice/products");

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authWithRoles(List.of("USER"))))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0403-A");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/products");
    }

    @Test
    void jwtWithoutRealmAccessClaimGets403() {
        var exchange = exchange("/api/v1/backoffice/tax-classes");
        var auth = new JwtAuthenticationToken(jwt(null), List.of());

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void resourceAccessAdminRoleFallbackPasses() {
        var exchange = exchange("/api/v1/backoffice/shipments");
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("svc-account")
                .claim("resource_access", Map.of("realm-management", Map.of("roles", List.of("ADMIN"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingSecurityContextGets403() {
        var exchange = exchange("/api/v1/backoffice/payments");

        filter.filter(exchange, passingChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonJwtPrincipalGets403() {
        var exchange = exchange("/api/v1/backoffice/notifications");

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new TestingAuthenticationToken("someone", "n/a")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void roleCaseIsSignificant() {
        var exchange = exchange("/api/v1/backoffice/ratings");

        filter.filter(exchange, passingChain())
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authWithRoles(List.of("admin"))))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filterOrderFollowsBindingConstant() {
        assertThat(filter.getOrder())
                .isEqualTo(FilterOrder.ADMIN_ROLE_GATE)
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private GatewayFilterChain passingChain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(invocation -> {
            var exchange = invocation.getArgument(0, ServerWebExchange.class);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });
        return chain;
    }

    private Authentication authWithRoles(List<String> roles) {
        // 2-arg constructor: Security 7 marks only authority-backed tokens authenticated,
        // mirroring what JwtReactiveAuthenticationManager puts into the context
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt(roles), authorities);
    }

    private Authentication userAuth() {
        return authWithRoles(List.of("USER"));
    }

    private Jwt jwt(List<String> roles) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (roles != null) {
            builder.claim("realm_access", Map.of("roles", roles));
        }
        return builder.build();
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("http://gateway.local" + path).build());
    }
}
