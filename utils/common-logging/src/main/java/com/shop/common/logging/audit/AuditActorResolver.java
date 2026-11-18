package com.shop.common.logging.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AuditActorResolver {

    static final String CLAIM_SUB = "sub";
    static final String CLAIM_CLIENT_ID = "clientId";
    static final String CLAIM_AZP = "azp";
    static final String CLAIM_SESSION_STATE = "session_state";
    static final String CLAIM_SID = "sid";

    static final AuditActorResolver.Actor ANONYMOUS = new Actor("anonymous", AuditEvent.ACTOR_TYPE_USER);

    private AuditActorResolver() {
    }

    public static Actor resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return ANONYMOUS;
        }
        String clientId = stringClaim(jwt, CLAIM_CLIENT_ID);
        if (clientId != null) {
            return new Actor(clientId, AuditEvent.ACTOR_TYPE_SERVICE);
        }
        String azp = stringClaim(jwt, CLAIM_AZP);
        if (azp != null && !hasUserSession(jwt)) {
            return new Actor(azp, AuditEvent.ACTOR_TYPE_SERVICE);
        }
        String sub = stringClaim(jwt, CLAIM_SUB);
        if (sub != null) {
            return new Actor(sub, AuditEvent.ACTOR_TYPE_USER);
        }
        return ANONYMOUS;
    }

    private static boolean hasUserSession(Jwt jwt) {
        return jwt.hasClaim(CLAIM_SESSION_STATE) || jwt.hasClaim(CLAIM_SID);
    }

    private static String stringClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    public record Actor(String id, String type) {
    }
}
