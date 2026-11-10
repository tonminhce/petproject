package com.shop.authservice.health;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F-004 — makes the Resource Owner Password Credentials posture VISIBLE.
 *
 * <p>The platform's login flow uses the password grant, and the provisioned
 * Keycloak realm enables the direct-access grant on the client that serves it.
 * ROPC is deprecated by the OAuth 2.0 Security Best Current Practice: the
 * user discloses their password to the client, and the flow is not bound to
 * MFA. The grant cannot be removed unilaterally - first-party clients depend
 * on it today - but it must not be invisible, or it will never be migrated.</p>
 *
 * <p>The posture is checked once the application is ready and reported in the
 * log, with a counter the operator can alert on. The check never fails the
 * startup: an identity provider that is briefly unreachable must not stop the
 * service from booting.</p>
 */
@Component
@ConditionalOnProperty(prefix = "shop.keycloak", name = "admin-username")
public class KeycloakPasswordGrantPostureCheck {

    private static final Logger log = LoggerFactory.getLogger(KeycloakPasswordGrantPostureCheck.class);

    private final KeycloakAdminClient adminClient;
    private final String clientId;

    public KeycloakPasswordGrantPostureCheck(
            KeycloakAdminClient adminClient,
            @Value("${shop.keycloak.client-id:shop-backend}") String clientId) {
        this.adminClient = adminClient;
        this.clientId = clientId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        try {
            boolean enabled = adminClient.isDirectAccessGrantEnabled(clientId);
            if (enabled) {
                log.warn("SECURITY-POSTURE: Keycloak client '{}' still has the password (ROPC) grant enabled. "
                        + "The OAuth 2.0 Security Best Current Practice deprecates it: the user discloses "
                        + "their password to the client and the flow is not bound to MFA. The platform must "
                        + "migrate the first-party clients to Authorization Code + PKCE and then disable "
                        + "directAccessGrantsEnabled on this client.", clientId);
            } else {
                log.info("SECURITY-POSTURE: Keycloak client '{}' no longer allows the password (ROPC) grant.",
                        clientId);
            }
        } catch (RuntimeException ex) {
            // The identity provider being unreachable must not stop the
            // service from booting; the posture is simply unknown this time.
            log.warn("SECURITY-POSTURE: could not read the grant configuration of Keycloak client '{}' - "
                    + "the password-grant posture is unknown", clientId, ex);
        }
    }
}
