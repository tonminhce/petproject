package com.shop.common.security.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.jwt.JwtClaimExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

public class ServiceCallerGuard {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallerGuard.class);

    private static final List<String> HUMAN_ADMIN_ROLES = List.of("ADMIN", "MANAGER");

    public void requireCaller(String... allowedClientIds) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        requireEstablishedPrincipal(authentication);
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

    public String clientIdOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return JwtClaimExtractor.authorizedParty(jwtAuthentication.getToken());
        }
        return null;
    }

    private void requireEstablishedPrincipal(Authentication authentication) {
        if (authentication == null) {
            log.warn("Rejected a service call with an empty security context");
            throw BusinessException.of(ErrorCode.UNAUTHORIZED, "caller is not authenticated");
        }
        if (!authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            log.warn("Rejected a service call from an unauthenticated principal");
            throw BusinessException.of(ErrorCode.UNAUTHORIZED, "caller is not authenticated");
        }
        if (authentication.getPrincipal() == null) {
            log.warn("Rejected a service call whose authentication carries no principal");
            throw BusinessException.of(ErrorCode.UNAUTHORIZED, "caller has no principal");
        }
    }

    private boolean hasHumanAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .anyMatch(HUMAN_ADMIN_ROLES::contains);
    }

}
