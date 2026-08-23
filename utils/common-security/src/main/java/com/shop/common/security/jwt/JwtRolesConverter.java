package com.shop.common.security.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps Keycloak's {@code realm_access.roles} JWT claim into Spring Security's
 * {@link GrantedAuthority} set.
 *
 * <p>For every realm role, the converter emits <strong>two</strong> authorities:</p>
 * <ul>
 *   <li>The raw role name — usable via {@code .hasAuthority("admin")}</li>
 *   <li>The {@code ROLE_}-prefixed form — usable via {@code .hasRole("admin")}</li>
 * </ul>
 *
 * <p>Emitting both shapes keeps the converter compatible with services that
 * mix the two styles — pick whichever reads better at the call site.</p>
 *
 * <p>If the token has no {@code realm_access} claim or the roles list is empty,
 * the converter returns an empty collection (Spring Security treats the user
 * as authenticated but without authorities, which is exactly what we want for
 * "logged in but not authorised for anything").</p>
 */
public final class JwtRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";
    static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess == null) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get(ROLES);
        if (!(rolesClaim instanceof List<?> rawRoles) || rawRoles.isEmpty()) {
            return List.of();
        }
        return extractAuthorities(rawRoles);
    }

    private static Collection<GrantedAuthority> extractAuthorities(List<?> rawRoles) {
        List<GrantedAuthority> authorities = new ArrayList<>(rawRoles.size() * 2);
        for (Object raw : rawRoles) {
            if (raw == null) {
                continue;
            }
            String role = Objects.toString(raw).trim();
            if (role.isEmpty()) {
                continue;
            }
            authorities.add(new SimpleGrantedAuthority(role));
            authorities.add(new SimpleGrantedAuthority(
                    role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role));
        }
        return authorities;
    }
}
