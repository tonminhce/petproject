package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.constant.ServiceRoute;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * D1 — backoffice edge role gate. Requests under one of the 9 backoffice
 * prefixes must carry an ADMIN realm role, mirroring the fleet's
 * {@code JwtRolesConverter} (realm_access.roles) with a resource_access
 * fallback for client-scoped roles. Anything else on those prefixes gets the
 * fleet 403 envelope.
 *
 * <p>401 (no/invalid token) is already handled upstream by the resource-server
 * security chain — this filter only ever sees authenticated requests.</p>
 */
public final class AdminRoleGateFilter implements GlobalFilter, Ordered {

    static final String ADMIN_ROLE = "ADMIN";
    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";

    private final List<String> backofficePrefixes;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public AdminRoleGateFilter(final GatewayErrorResponseWriter errorResponseWriter) {
        this.backofficePrefixes = ServiceRoute.backofficeRoutes().map(ServiceRoute::prefix).toList();
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String path = exchange.getRequest().getPath().value();
        if (backofficePrefixes.stream().noneMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
            return chain.filter(exchange);
        }

        final Mono<Boolean> allowed = ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(this::isAdmin)
                .defaultIfEmpty(false);

        return allowed.flatMap(isAdminAllowed -> isAdminAllowed
                ? chain.filter(exchange)
                : errorResponseWriter.write(exchange, ErrorCode.ACCESS_DENIED));
    }

    @Override
    public int getOrder() {
        return FilterOrder.ADMIN_ROLE_GATE;
    }

    private boolean isAdmin(final Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return hasRealmAdminRole(jwt) || hasResourceAdminRole(jwt);
    }

    /**
     * Mirrors {@code JwtRolesConverter}: Keycloak realm roles live under
     * {@code realm_access.roles} and the fleet checks the raw role name.
     */
    private boolean hasRealmAdminRole(final Jwt jwt) {
        return rolesContain(jwt.getClaimAsMap(REALM_ACCESS));
    }

    /**
     * Fallback: client roles under {@code resource_access.<client>.roles}.
     */
    private boolean hasResourceAdminRole(final Jwt jwt) {
        final Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS);
        if (resourceAccess == null) {
            return false;
        }
        return resourceAccess.values().stream().anyMatch(this::rolesContain);
    }

    private boolean rolesContain(final Object accessClaim) {
        if (!(accessClaim instanceof Map<?, ?> access)) {
            return false;
        }
        return access.get(ROLES) instanceof List<?> roles && roles.contains(ADMIN_ROLE);
    }
}
