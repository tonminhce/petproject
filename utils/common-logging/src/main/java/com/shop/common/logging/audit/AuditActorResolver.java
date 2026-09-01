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
 * <p>Claim order (final-review F3, KC26 live probe): {@code clientId} means a
 * service token outright. {@code azp} means service ONLY when the token
 * carries no user-session marker ({@code session_state}/{@code sid}): the
 * KC26 probe showed machine tokens have {@code clientId=None},
 * {@code azp=&lt;client&gt;}, {@code sub=&lt;service-account-uuid&gt;} — so
 * {@code sub} must not outrank {@code azp} — but {@code azp} names the
 * authorized party on INTERACTIVE user tokens too, so without the session
 * discriminator it would mislabel every human actor as {@code service}.
 * Keycloak client_credentials tokens carry no session; interactive grants
 * always do. {@code sub} resolves a session-bearing token to
 * {@code user}. Tokens without any resolvable claim (or with no
 * authentication at all) resolve to {@code anonymous}/{@code user}.</p>
 *
 * <p>All Spring Security types are referenced only inside method bodies; this
 * class is only instantiated when the audit aspect is active, which the
 * autoconfiguration guards with {@code @ConditionalOnClass}.</p>
 */
public final class AuditActorResolver {

    static final String CLAIM_SUB = "sub";
    static final String CLAIM_CLIENT_ID = "clientId";
    static final String CLAIM_AZP = "azp";
    static final String CLAIM_SESSION_STATE = "session_state";
    static final String CLAIM_SID = "sid";

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

    /**
     * Interactive Keycloak grants stamp a session marker on the token;
     * client_credentials machine tokens have none.
     */
    private static boolean hasUserSession(Jwt jwt) {
        return jwt.hasClaim(CLAIM_SESSION_STATE) || jwt.hasClaim(CLAIM_SID);
    }

    private static String stringClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    /** PII-free actor identity: an opaque id plus {@code user|service}. */
    public record Actor(String id, String type) {
    }
}
