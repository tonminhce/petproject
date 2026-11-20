package com.shop.common.spring.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedDefaultsSecurityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> shared() throws Exception {
        try (InputStream in = new ClassPathResource("application-shared.yml").getInputStream()) {
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
    void theShippedExpectedClientIdDefaultIsNarrowAndNeverEmpty() throws Exception {
        Map<String, Object> shop = (Map<String, Object>) shared().get("shop");
        Map<String, Object> security = (Map<String, Object>) shop.get("security");
        String expectedClientIds = String.valueOf(security.get("expected-client-ids"));

        assertThat(expectedClientIds)
                .as("the shipped default must stay non-empty so the azp validator is never skipped")
                .isEqualTo("$" + "{SHOP_SECURITY_EXPECTED_CLIENT_IDS:ecommerce-client}");
    }

    @Test
    void theShippedForwardHeadersStrategyNeverTrustsACallerControlledRemoteAddress() throws Exception {
        Map<String, Object> server = (Map<String, Object>) shared().get("server");

        assertThat(String.valueOf(server.get("forward-headers-strategy")))
                .as("the framework must not rewrite the remote address from a caller-controlled header")
                .isEqualTo("none");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyPerServiceAudienceIsNarrowerThanTheLegacyFleetWideList() throws Exception {
        Map<String, Object> shop = (Map<String, Object>) shared().get("shop");
        Map<String, Object> security = (Map<String, Object>) shop.get("security");
        Map<String, Object> audience = (Map<String, Object>) security.get("audience");
        Map<String, Object> perService = (Map<String, Object>) audience.get("per-service-client-ids");

        List<String> legacyFleetWideList = List.of("ecommerce-client", "order-service", "rating-service",
                "search-service", "product-service", "media-service");

        assertThat(perService).isNotEmpty();
        for (Map.Entry<String, Object> entry : perService.entrySet()) {
            List<String> clientIds = (List<String>) entry.getValue();
            assertThat(clientIds).as("%s must declare its callers", entry.getKey()).isNotEmpty();
            assertThat(clientIds).as("%s must only accept clients of the legacy realm", entry.getKey())
                    .allMatch(legacyFleetWideList::contains);
            assertThat(clientIds).as("%s must be narrower than the legacy fleet-wide list", entry.getKey())
                    .hasSizeLessThan(legacyFleetWideList.size());
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