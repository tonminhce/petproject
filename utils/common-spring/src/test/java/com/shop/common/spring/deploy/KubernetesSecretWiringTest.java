package com.shop.common.spring.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kubernetes manifests must not let a service fall back to a shipped
 * credential.
 *
 * <p>A manifest that declares only the datasource URL leaves the username and
 * the password on the application defaults, and the application defaults are
 * the published literals this repository ships. Every credential a deployment
 * needs has to come from the secret.</p>
 */
class KubernetesSecretWiringTest {

    private static final Pattern CONTAINER = Pattern.compile(
            "(?m)^        - name: ([\\w-]+)\\R          image: shop/\\1:latest\\R(?<body>.*?)(?=^        - name: |\\z)",
            Pattern.DOTALL);

    private static final Pattern SECRET_REF = Pattern.compile(
            "secretKeyRef:\\R\\s+name: shop-system-secrets\\R\\s+key: ([A-Z0-9_]+)");

    @Test
    @DisplayName("every service deployment takes its datasource password from the secret")
    void everyServiceDeploymentTakesItsDatasourcePasswordFromTheSecret() throws IOException {
        String manifest = microservicesManifest();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String service : services(manifest)) {
            String body = body(manifest, service);
            if (!body.contains("SPRING_DATASOURCE_URL")) {
                continue;
            }
            scanned++;
            if (!body.contains("SPRING_DATASOURCE_USERNAME")) {
                offenders.add(service + " -> no SPRING_DATASOURCE_USERNAME");
            }
            if (!body.contains("SPRING_DATASOURCE_PASSWORD")) {
                offenders.add(service + " -> no SPRING_DATASOURCE_PASSWORD");
            }
            if (!body.contains("key: POSTGRES_PASSWORD")) {
                offenders.add(service + " -> the password is not taken from the secret");
            }
        }

        assertThat(scanned)
                .as("the scan must actually find the deployments")
                .isGreaterThan(0);
        assertThat(offenders)
                .as("a deployment without the secret wiring falls back to the published datasource password")
                .isEmpty();
    }

    @Test
    @DisplayName("the service-to-service client secrets are taken from the secret")
    void theServiceToServiceClientSecretsAreTakenFromTheSecret() throws IOException {
        String manifest = microservicesManifest();

        for (String secret : List.of("ORDER_SERVICE_CLIENT_SECRET", "RATING_SERVICE_CLIENT_SECRET",
                "SEARCH_SERVICE_CLIENT_SECRET", "PRODUCT_SERVICE_CLIENT_SECRET", "MEDIA_SERVICE_CLIENT_SECRET")) {
            assertThat(manifest)
                    .as("%s must be wired from the secret", secret)
                    .contains("name: " + secret)
                    .contains("key: " + secret);
        }
    }

    @Test
    @DisplayName("a deployment only requests the client secret of its own service")
    void aDeploymentOnlyRequestsTheClientSecretOfItsOwnService() throws IOException {
        String manifest = microservicesManifest();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String service : services(manifest)) {
            String body = body(manifest, service);
            Matcher matcher = Pattern.compile(
                    "- name: ([A-Z0-9_]*CLIENT_SECRET)\\R(?:.*\\R){0,4}?\\s+key: ([A-Z0-9_]+)")
                    .matcher(body);
            while (matcher.find()) {
                scanned++;
                String envName = matcher.group(1);
                String secretKey = matcher.group(2);
                if (!envName.equals(secretKey)) {
                    offenders.add(service + " -> " + envName + " reads the secret key " + secretKey);
                }
                if (!envName.startsWith(service.toUpperCase().replace('-', '_') + "_")) {
                    offenders.add(service + " -> requests the foreign credential " + envName);
                }
            }
        }

        assertThat(scanned)
                .as("the scan must actually find the client secret wirings")
                .isGreaterThan(0);
        assertThat(offenders)
                .as("a deployment must not hold the credential of another service")
                .isEmpty();
    }

    @Test
    @DisplayName("a deployment declares no environment variable twice")
    void aDeploymentDeclaresNoEnvironmentVariableTwice() throws IOException {
        String manifest = microservicesManifest();

        List<String> offenders = new ArrayList<>();
        for (String service : services(manifest)) {
            String body = body(manifest, service);
            List<String> names = new ArrayList<>();
            Matcher matcher = Pattern.compile("(?m)^            - name: ([A-Z0-9_]+)\\R").matcher(body);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String name : names) {
                if (!seen.add(name)) {
                    offenders.add(service + " -> " + name + " is declared twice");
                }
            }
        }

        assertThat(offenders)
                .as("a duplicated environment entry hides which value actually reaches the process")
                .isEmpty();
    }

    @Test
    @DisplayName("the gateway deployment takes the admin IP allowlist from the secret")
    void theGatewayDeploymentTakesTheAdminIpAllowlistFromTheSecret() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path gateway = repoRoot.resolve("k8s/gateway-service.yaml");
        Assumptions.assumeTrue(Files.exists(gateway), "k8s/gateway-service.yaml not found - contract not checked");

        String content = Files.readString(gateway, StandardCharsets.UTF_8);

        assertThat(content)
                .as("the gateway refuses to start in production without the allowlist")
                .contains("ADMIN_IP_ALLOWLIST");
        assertThat(content)
                .as("the allowlist must come from the secret")
                .contains("key: ADMIN_IP_ALLOWLIST");
    }

    @Test
    @DisplayName("the secret template ships no usable credential value")
    void theSecretTemplateShipsNoUsableCredentialValue() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path template = repoRoot.resolve("k8s/secrets.template.yaml");
        Assumptions.assumeTrue(Files.exists(template), "k8s/secrets.template.yaml not found - contract not checked");

        String content = Files.readString(template, StandardCharsets.UTF_8);

        int start = content.indexOf("stringData:");
        assertThat(start).as("the template must declare stringData").isPositive();
        String data = content.substring(start);

        List<String> offenders = new ArrayList<>();
        for (String line : data.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains(":")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty() || (value.startsWith("<") && value.endsWith(">"))) {
                continue;
            }
            offenders.add(key + " -> " + value);
        }

        assertThat(offenders)
                .as("a template value that is not an obvious placeholder is a usable credential")
                .isEmpty();
        assertThat(content)
                .as("the secret name must match the manifests")
                .contains("name: shop-system-secrets");
    }

    private static String microservicesManifest() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path manifest = repoRoot.resolve("k8s/microservices.yaml");
        Assumptions.assumeTrue(Files.exists(manifest), "k8s/microservices.yaml not found - contract not checked");
        return Files.readString(manifest, StandardCharsets.UTF_8);
    }

    private static Set<String> services(String manifest) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CONTAINER.matcher(manifest);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String body(String manifest, String service) {
        Matcher matcher = CONTAINER.matcher(manifest);
        while (matcher.find()) {
            if (matcher.group(1).equals(service)) {
                return matcher.group("body");
            }
        }
        return "";
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("utils"))
                    && Files.isDirectory(current.resolve("order-service"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
