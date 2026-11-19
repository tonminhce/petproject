package com.shop.authservice.health;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${shop.keycloak.admin-username:}' != '' "
        + "|| '${shop.keycloak.admin-client-secret:}' != ''")
public class KeycloakPasswordGrantPostureCheck {

    private static final Logger log = LoggerFactory.getLogger(KeycloakPasswordGrantPostureCheck.class);

    private final KeycloakAdminClient adminClient;
    private final String clientId;

    public KeycloakPasswordGrantPostureCheck(
            KeycloakAdminClient adminClient,
            // F-079: the default must name a client that exists in the
            // provisioned realm. "shop-backend" was never provisioned, so the
            // posture check measured a client that did not exist.
            @Value("${shop.keycloak.client-id:ecommerce-client}") String clientId) {
        this.adminClient = adminClient;
        this.clientId = clientId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        if (adminClient.hasServiceAccountCredentials()) {
            // F-078: the admin path uses the service account of the
            // application realm (client_credentials). No master-realm
            // administrator credential travels through a password grant, and
            // the permissions of the token are scoped to that realm.
            log.info("SECURITY-POSTURE: the admin path uses the service account of the application realm "
                    + "(client_credentials). The master-realm password grant is not used for admin calls.");
        }
        if (!adminClient.hasAdminCredentials() && !adminClient.hasServiceAccountCredentials()) {
            // F-006: the shared defaults ship no credential any more. Say so
            // explicitly - the admin API is unusable and the ROPC posture
            // cannot be read.
            log.warn("SECURITY-POSTURE: neither the admin service account "
                    + "(shop.keycloak.admin-client-secret) nor the master-realm admin credentials "
                    + "(shop.keycloak.admin-username / admin-password) are configured - "
                    + "the admin API is unusable and the ROPC posture cannot be checked.");
            return;
        }
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
