package com.shop.common.security.jwt;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * Immutable snapshot of the currently authenticated principal.
 *
 * <p>Built from a {@link Jwt} via {@link #fromCurrentJwt()} or directly via
 * {@link #from(Jwt)}. The fields mirror what most services need to make
 * authorization decisions without re-parsing the JWT themselves.</p>
 *
 * <p>This is a value object — safe to share between threads, never mutated.
 * It does <em>not</em> hold any reactive context; pull a fresh instance on
 * each request rather than caching.</p>
 */
public record AuthenticatedUser(
        String id,
        String username,
        String email,
        String fullName,
        List<String> realmRoles,
        List<? extends GrantedAuthority> authorities
) {

    /** Empty placeholder for the anonymous or pre-authentication case. */
    public static final AuthenticatedUser ANONYMOUS =
            new AuthenticatedUser("", "", "", "", List.of(), List.of());

    /** Build from a verified JWT (typically {@code jwtAuthentication.getToken()}). */
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

    /**
     * Read the current authentication from {@link SecurityContextHolder} and
     * snapshot it. Returns {@link #ANONYMOUS} when nothing is authenticated
     * or when the principal is not a JWT.
     */
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

    /** Convenience that throws {@link IllegalStateException} when anonymous. */
    public static AuthenticatedUser requireCurrent() {
        return current().orElseThrow(() ->
                new IllegalStateException("No authenticated user in security context"));
    }

    /** True when the user carries the given realm role (case-sensitive). */
    public boolean hasRole(String role) {
        return realmRoles.contains(role);
    }

    /** True when the user has any of the supplied realm roles. */
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
