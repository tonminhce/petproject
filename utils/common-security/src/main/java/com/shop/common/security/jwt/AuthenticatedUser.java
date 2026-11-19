package com.shop.common.security.jwt;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public record AuthenticatedUser(
        String id,
        String username,
        String email,
        String fullName,
        List<String> realmRoles,
        List<? extends GrantedAuthority> authorities
) {

    public static final AuthenticatedUser ANONYMOUS =
            new AuthenticatedUser("", "", "", "", List.of(), List.of());

    public static AuthenticatedUser from(Jwt jwt) {
        List<GrantedAuthority> granted = currentAuthorities().stream()
                .map(GrantedAuthority.class::cast)
                .toList();
        return new AuthenticatedUser(
                JwtClaimExtractor.subject(jwt),
                JwtClaimExtractor.username(jwt),
                JwtClaimExtractor.email(jwt),
                JwtClaimExtractor.fullName(jwt),
                JwtClaimExtractor.realmRoles(jwt),
                granted
        );
    }

    public static Optional<AuthenticatedUser> current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return Optional.of(from(jwtAuth.getToken()));
        }
        return Optional.empty();
    }

    public static AuthenticatedUser requireCurrent() {
        return current().orElseThrow(() ->
                new IllegalStateException("No authenticated user in security context"));
    }

    public boolean hasRole(String role) {
        return realmRoles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        for (String role : candidates) {
            if (realmRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    private static Collection<? extends GrantedAuthority> currentAuthorities() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? List.of() : auth.getAuthorities();
    }
}
