package com.shop.common.security.jwt;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Convenience accessor for the claims that every service reaches for first —
 * subject, email, full name, and realm roles.
 *
 * <p>Centralizes the claim-name knowledge in one place so the rest of the
 * codebase stops guessing whether the email claim is {@code email},
 * {@code preferred_username}, or something else entirely. Keycloak's defaults
 * are documented at
 * <a href="https://www.keycloak.org/docs/latest/server_admin/index.html#_oidc-token">OIDC Token</a>.</p>
 */
public final class JwtClaimExtractor {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    static final String CLAIM_GIVEN_NAME = "given_name";
    static final String CLAIM_FAMILY_NAME = "family_name";
    static final String CLAIM_FULL_NAME = "name";
    static final String CLAIM_ROLES = "realm_access";

    private JwtClaimExtractor() {
    }

    /** Subject — the stable, immutable Keycloak user id. */
    public static String subject(Jwt jwt) {
        return jwt.getSubject();
    }

    /** Email claim — empty string if absent or blank. */
    public static String email(Jwt jwt) {
        return stringClaim(jwt, CLAIM_EMAIL).orElse("");
    }

    /** Username — falls back to subject if {@code preferred_username} is absent. */
    public static String username(Jwt jwt) {
        return stringClaim(jwt, CLAIM_PREFERRED_USERNAME).orElseGet(() -> subject(jwt));
    }

    /**
     * Best-effort full name. Prefers {@code name}, then concatenates
     * {@code given_name} + {@code family_name} when available.
     */
    public static String fullName(Jwt jwt) {
        Optional<String> explicit = stringClaim(jwt, CLAIM_FULL_NAME);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        String given = stringClaim(jwt, CLAIM_GIVEN_NAME).orElse("");
        String family = stringClaim(jwt, CLAIM_FAMILY_NAME).orElse("");
        String joined = (given + " " + family).trim();
        return joined.isEmpty() ? username(jwt) : joined;
    }

    /** Realm roles as a snapshot list — never null. */
    @SuppressWarnings("unchecked")
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(CLAIM_ROLES);
        if (realmAccess == null) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get(JwtRolesConverter.ROLES);
        if (!(rolesClaim instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static Optional<String> stringClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }
}
