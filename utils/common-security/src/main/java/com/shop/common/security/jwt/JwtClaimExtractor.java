package com.shop.common.security.jwt;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JwtClaimExtractor {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    static final String CLAIM_GIVEN_NAME = "given_name";
    static final String CLAIM_FAMILY_NAME = "family_name";
    static final String CLAIM_FULL_NAME = "name";
    static final String CLAIM_ROLES = "realm_access";

    private JwtClaimExtractor() {
    }

    public static String subject(Jwt jwt) {
        return jwt.getSubject();
    }

    public static String email(Jwt jwt) {
        return stringClaim(jwt, CLAIM_EMAIL).orElse("");
    }

    public static String username(Jwt jwt) {
        return stringClaim(jwt, CLAIM_PREFERRED_USERNAME).orElseGet(() -> subject(jwt));
    }

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
