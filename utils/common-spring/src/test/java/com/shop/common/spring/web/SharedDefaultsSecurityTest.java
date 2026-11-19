package com.shop.common.spring.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared defaults must not ship a credential or a wildcard origin.
 *
 * <p>F-006: {@code shop.keycloak.admin-username/admin-password} defaulted to
 * admin/admin - a master-realm takeover waiting to happen in any deployment
 * that forgot to override them - and the CORS origin list defaulted to
 * {@code *}, which lets any site read the unauthenticated endpoints of the
 * platform.</p>
 */
class SharedDefaultsSecurityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> shared() throws Exception {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            return new Yaml().load(in);
        }
    }

    @Test
    void noKeycloakAdminCredentialIsShippedAsADefault() throws Exception {
        Map<String, Object> shop = (Map<String, Object>) shared().get("shop");
        Map<String, Object> keycloak = (Map<String, Object>) shop.get("keycloak");

        for (String key : List.of("admin-username", "admin-password")) {
            String value = String.valueOf(keycloak.get(key));
            assertThat(value)
                    .as("%s must not carry a credential default", key)
                    .doesNotContain(":admin")
                    .doesNotContain(":password")
                    .doesNotContain(":changeme")
                    .matches("\\$\\{[A-Z_]+:\\}");
        }
    }

    @Test
    void theShippedCorsOriginListIsNeverAWildcard() throws Exception {
        Map<String, Object> shop = (Map<String, Object>) shared().get("shop");
        Map<String, Object> security = (Map<String, Object>) shop.get("security");
        Map<String, Object> cors = (Map<String, Object>) security.get("cors");

        @SuppressWarnings("unchecked")
        List<String> origins = (List<String>) cors.get("allowed-origin-patterns");
        assertThat(origins).isNotEmpty();
        for (String origin : origins) {
            assertThat(origin)
                    .as("the shipped CORS default must be an explicit origin list")
                    .doesNotContain("*")
                    .contains("http://localhost");
        }
    }
}
