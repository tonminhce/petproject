package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.constant.ServiceRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

public final class AdminRoleGateFilter implements GlobalFilter, Ordered {

    static final String ADMIN_ROLE = "ADMIN";
    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";

    private static final Logger log = LoggerFactory.getLogger(AdminRoleGateFilter.class);

    private final List<String> backofficePrefixes;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public AdminRoleGateFilter(final GatewayErrorResponseWriter errorResponseWriter) {
        this.backofficePrefixes = ServiceRoute.backofficeRoutes().map(ServiceRoute::prefix).toList();
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String rawPath = exchange.getRequest().getPath().value();
        if (RequestPathGuard.containsMatrixVariable(rawPath)) {
            log.warn("Rejected matrix-variable path at the backoffice role gate: {}", rawPath);
            return errorResponseWriter.write(exchange, ErrorCode.BAD_REQUEST);
        }
        final String decodedPath = RequestPathGuard.decoded(rawPath);
        final boolean backoffice = backofficePrefixes.stream()
                .anyMatch(prefix -> decodedPath.equals(prefix) || decodedPath.startsWith(prefix + "/"));
        if (!backoffice) {
            return chain.filter(exchange);
        }
        if (RequestPathGuard.isEncoded(rawPath)) {
            log.warn("Rejected percent-encoded path under a backoffice prefix: {}", rawPath);
            return errorResponseWriter.write(exchange, ErrorCode.BAD_REQUEST);
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

    private boolean hasRealmAdminRole(final Jwt jwt) {
        return rolesContain(jwt.getClaimAsMap(REALM_ACCESS));
    }

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
