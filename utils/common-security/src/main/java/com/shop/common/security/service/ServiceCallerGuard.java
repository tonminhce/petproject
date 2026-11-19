package com.shop.common.security.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

public class ServiceCallerGuard {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallerGuard.class);

    /** The realm roles that act as a human administrator rather than a service. */
    private static final List<String> HUMAN_ADMIN_ROLES = List.of("ADMIN", "MANAGER");

    /**
     * Assert that the current caller is one of the allowed service clients.
     *
     * @param allowedClientIds client ids permitted to invoke this operation
     * @throws BusinessException with {@link ErrorCode#FORBIDDEN} when the caller
     *         is neither an administrator nor an allowed service client
     */
    public void requireCaller(String... allowedClientIds) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // No authentication at all: the endpoint's own @PreAuthorize is the
            // authority on whether the request may proceed, and the guard only
            // binds an AUTHENTICATED service caller to its owner.
            return;
        }
        if (!authentication.isAuthenticated()) {
            throw BusinessException.of(ErrorCode.FORBIDDEN, "caller is not authenticated");
        }
        if (hasHumanAdminRole(authentication)) {
            return;
        }
        String clientId = clientIdOf(authentication);
        if (clientId == null || clientId.isBlank()) {
            log.warn("Rejected a service call whose token carries no client identity (azp)");
            throw BusinessException.of(ErrorCode.FORBIDDEN, "caller has no client identity");
        }
        List<String> allowed = List.of(allowedClientIds);
        if (!allowed.contains(clientId)) {
            log.warn("Rejected a service call from client '{}' - allowed: {}", clientId, allowed);
            throw BusinessException.of(ErrorCode.FORBIDDEN, "client " + clientId + " may not call this operation");
        }
    }

    /** The {@code azp} claim - the client the token was issued to. */
    public String clientIdOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getClaimAsString("azp");
        }
        return null;
    }

    private boolean hasHumanAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .anyMatch(HUMAN_ADMIN_ROLES::contains);
    }

}
