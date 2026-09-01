package com.shop.common.logging.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the audit actor from the current Spring Security context.
 *
 * <p>Binding rules (spec D6): a JWT user token resolves to {@code sub} with
 * actor type {@code user}; a service token resolves to the {@code clientId}
 * (or {@code azp} fallback) claim with type {@code service}. Email, phone and
 * name claims are NEVER read for the identifier — they may exist on the token
 * but are deliberately ignored here so they can never reach the audit line.</p>
 *
 * <p>Claim order: {@code clientId} beats {@code sub} because Keycloak service
 * tokens carry a service-account {@code sub} as well — its presence must not
 * mislabel a client-credentials token as a user. Tokens without any of the
 * three claims (or with no authentication at all) resolve to
 * {@code anonymous}/{@code user}.</p>
 *
 * <p>All Spring Security types are referenced only inside method bodies; this
 * class is only instantiated when the audit aspect is active, which the
 * autoconfiguration guards with {@code @ConditionalOnClass}.</p>
 */
public final class AuditActorResolver {

    static final String CLAIM_SUB = "sub";
    static final String CLAIM_CLIENT_ID = "clientId";
    static final String CLAIM_AZP = "azp";

    /** PII-free placeholder for unauthenticated invocations. */
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
        String sub = stringClaim(jwt, CLAIM_SUB);
        if (sub != null) {
            return new Actor(sub, AuditEvent.ACTOR_TYPE_USER);
        }
        String azp = stringClaim(jwt, CLAIM_AZP);
        if (azp != null) {
            return new Actor(azp, AuditEvent.ACTOR_TYPE_SERVICE);
        }
        return ANONYMOUS;
    }

    private static String stringClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    /** PII-free actor identity: an opaque id plus {@code user|service}. */
    public record Actor(String id, String type) {
    }
}
