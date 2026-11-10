package com.shop.common.security.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

/**
 * F-041 (P1) — binds a service-to-service call to the service that owns it.
 *
 * <p>Every service client in the Keycloak realm carries the single realm role
 * {@code SERVICE}. A token minted for {@code order-service} was therefore
 * accepted by every {@code @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")}
 * endpoint in the fleet — including {@code PaymentController.capture} and
 * {@code PaymentController.refund} on ANY payment id. One leaked service
 * secret granted fleet-wide administrative power over money and stock.</p>
 *
 * <p>The provisioned realm emits no {@code aud} claim, so the only usable
 * client discriminator is {@code azp} — the client the token was actually
 * issued to. This guard checks it against the clients that are allowed to
 * invoke the operation.</p>
 *
 * <p>Human administrators ({@code ADMIN} / {@code MANAGER}) are unaffected:
 * they act through the backoffice, not as a service identity.</p>
 */
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
