package com.shop.searchservice.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentCredentialContractTest {

    private static final Path REPO_ROOT = repoRoot();

    private static final List<String> COMPOSE_REQUIRED = List.of(
        "POSTGRES_PASSWORD",
        "REDIS_PASSWORD",
        "KEYCLOAK_ADMIN",
        "KEYCLOAK_ADMIN_PASSWORD",
        "ADMIN_SERVICE_CLIENT_SECRET",
        "ORDER_SERVICE_CLIENT_SECRET",
        "RATING_SERVICE_CLIENT_SECRET",
        "SEARCH_SERVICE_CLIENT_SECRET",
        "PRODUCT_SERVICE_CLIENT_SECRET",
        "MEDIA_SERVICE_CLIENT_SECRET",
        "KEYCLOAK_TEST_USER_PASSWORD",
        "KEYCLOAK_ADMIN_USER_PASSWORD",
        "STORAGE_ACCESS_KEY",
        "STORAGE_SECRET_KEY",
        "ES_ADMIN_USERNAME",
        "ES_ADMIN_PASSWORD",
        "PAYMENT_WEBHOOK_SECRET",
        "SHIPMENT_WEBHOOK_SECRET_GHN",
        "SHIPMENT_WEBHOOK_SECRET_GHTK",
        "GRAFANA_ADMIN_PASSWORD");

    private static final List<String> TEMPLATE_BLANK = List.of(
        "POSTGRES_PASSWORD",
        "REDIS_PASSWORD",
        "KEYCLOAK_ADMIN",
        "KEYCLOAK_ADMIN_PASSWORD",
        "ADMIN_SERVICE_CLIENT_SECRET",
        "ORDER_SERVICE_CLIENT_SECRET",
        "RATING_SERVICE_CLIENT_SECRET",
        "SEARCH_SERVICE_CLIENT_SECRET",
        "PRODUCT_SERVICE_CLIENT_SECRET",
        "MEDIA_SERVICE_CLIENT_SECRET",
        "KEYCLOAK_TEST_USER_PASSWORD",
        "KEYCLOAK_ADMIN_USER_PASSWORD",
        "STORAGE_ACCESS_KEY",
        "STORAGE_SECRET_KEY",
        "ES_ADMIN_USERNAME",
        "ES_ADMIN_PASSWORD",
        "PAYMENT_WEBHOOK_SECRET",
        "SHIPMENT_WEBHOOK_SECRET_GHN",
        "SHIPMENT_WEBHOOK_SECRET_GHTK",
        "GRAFANA_ADMIN_PASSWORD",
        "MAIL_PASSWORD",
        "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET",
        "TELEGRAM_BOT_TOKEN",
        "TELEGRAM_CHAT_ID",
        "TELEGRAM_CI_CHAT_ID");

    private static final List<String> CREDENTIAL_SEGMENTS = List.of(
        "PASSWORD", "SECRET", "ACCESS_KEY", "API_KEY", "TOKEN", "PRIVATE_KEY");

    private static final Pattern CREDENTIAL_KEY =
        Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern ENV_ENTRY =
        Pattern.compile("^([A-Z0-9_]+)=(.*)$");
    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\$\\{([A-Z0-9_]+)}");
    private static final Pattern INTERPOLATION =
        Pattern.compile("\\$\\{([A-Z0-9_]+)(:[?-][^}]*)?}");

    @Test
    @DisplayName("the env template ships no credential value")
    void theEnvTemplateShipsNoCredentialValue() throws IOException {
        Map<String, String> values = envValues(".env.example");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!isCredentialKey(entry.getKey())) {
                continue;
            }
            String value = entry.getValue().trim();
            if (!value.isEmpty() && !PLACEHOLDER.matcher(value).matches()) {
                offenders.add(entry.getKey());
            }
        }

        assertThat(offenders)
            .as("a committed credential value authenticates against a provisioned account")
            .isEmpty();
    }

    @Test
    @DisplayName("every account the deployment requires is blank in the env template")
    void everyRequiredAccountIsBlankInTheEnvTemplate() throws IOException {
        Map<String, String> values = envValues(".env.example");

        List<String> offenders = new ArrayList<>();
        for (String credential : TEMPLATE_BLANK) {
            String value = values.get(credential);
            if (value == null) {
                offenders.add(credential + " is not declared");
            } else if (!value.trim().isEmpty()) {
                offenders.add(credential + " carries a value");
            }
        }

        assertThat(offenders)
            .as("the operator has to supply every credential the stack requires")
            .isEmpty();
    }

    @Test
    @DisplayName("the compose deployment never falls back to a hardcoded credential")
    void theComposeDeploymentNeverFallsBackToAHardcodedCredential() throws IOException {
        String compose = Files.readString(REPO_ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        for (String credential : COMPOSE_REQUIRED) {
            Matcher matcher = INTERPOLATION.matcher(compose);
            while (matcher.find()) {
                if (!credential.equals(matcher.group(1))) {
                    continue;
                }
                String fallback = matcher.group(2);
                if (fallback != null && fallback.startsWith(":-")) {
                    offenders.add(credential + " falls back to " + fallback);
                }
            }
        }

        assertThat(offenders)
            .as("a hardcoded fallback publishes a credential in every deployment")
            .isEmpty();
    }

    @Test
    @DisplayName("the database account name is required by the database service")
    void theDatabaseAccountNameIsRequiredByTheDatabaseService() {
        Map<String, Object> postgres = service(compose(), "postgres");
        @SuppressWarnings("unchecked")
        Map<String, Object> environment = (Map<String, Object>) postgres.get("environment");

        assertThat(String.valueOf(environment.get("POSTGRES_USER")))
            .as("the account name the database provisions must come from the environment")
            .contains("${POSTGRES_USER:?");
    }

    @Test
    @DisplayName("the realm import carries no literal secret and no literal password")
    void theRealmImportCarriesNoLiteralCredential() throws IOException {
        String realm = Files.readString(
            REPO_ROOT.resolve("docker/keycloak/import/ecommerce-realm.json"), StandardCharsets.UTF_8);

        List<String> literals = new ArrayList<>();
        Matcher matcher = Pattern.compile(
            "\"(secret|value)\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(realm);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (!value.isBlank() && !PLACEHOLDER.matcher(value).matches()) {
                literals.add(matcher.group(1) + " -> " + value);
            }
        }

        assertThat(literals)
            .as("a literal realm credential is published with every deployment")
            .isEmpty();
    }

    @Test
    @DisplayName("the payment service refuses a live-mode Stripe credential")
    void thePaymentServiceRefusesALiveModeStripeCredential() throws Exception {
        assertThat(guardSource())
            .as("the payment service must refuse a live-mode key in the application itself")
            .contains("sk_live_")
            .contains("shop.payment.allow-live-stripe-keys");
    }

    @Test
    @DisplayName("a live-mode Stripe credential is only allowed in an explicitly allowed context")
    void aLiveModeStripeCredentialNeedsAnExplicitOptIn() throws Exception {
        String source = guardSource();

        assertThat(source)
            .as("the opt-in must be read from the deployment configuration")
            .contains("shop.payment.allow-live-stripe-keys:false");
        assertThat(source)
            .as("a live-mode key without the opt-in must fail the startup")
            .contains("IllegalStateException");
    }

    @Test
    @DisplayName("the guard is wired into the service that holds the Stripe credential")
    void theGuardIsWiredIntoThePaymentService() throws Exception {
        Map<String, Object> compose = compose();

        Map<String, Object> paymentService = service(compose, "payment-service");
        Map<String, Object> environment = (Map<String, Object>) paymentService.get("environment");

        assertThat(environment)
            .as("the payment service is the holder of the Stripe credential")
            .containsKey("SHOP_PAYMENT_STRIPE_SECRET_KEY");
        assertThat(guardSource())
            .as("the guard must live in the payment service module, not only in the compose entrypoint")
            .isNotEmpty();
    }

    private static String guardSource() throws Exception {
        Path guard = Path.of("..", "payment-service", "src", "main", "java", "com", "shop",
            "paymentservice", "config", "PaymentStripeLiveKeyGuard.java");
        assertThat(Files.exists(guard))
            .as("the payment service must own the live-mode Stripe guard")
            .isTrue();
        return Files.readString(guard);
    }

    @SuppressWarnings("unused")
    private static int runGuard(String guard, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", guard, "true");
        builder.redirectErrorStream(true);
        Map<String, String> processEnvironment = builder.environment();
        processEnvironment.keySet().removeIf(key ->
            key.equals("STRIPE_SECRET_KEY")
                || key.equals("SHOP_PAYMENT_STRIPE_SECRET_KEY")
                || key.equals("SHOP_PAYMENT_ALLOW_LIVE_STRIPE_KEYS"));
        processEnvironment.putAll(environment);

        Process process = builder.start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("the Stripe guard did not terminate");
        }
        return process.exitValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> compose, String name) {
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return (Map<String, Object>) services.get(name);
    }

    private static boolean isCredentialKey(String key) {
        if (!CREDENTIAL_KEY.matcher(key).matches()) {
            return false;
        }
        for (String segment : key.split("_")) {
            if (CREDENTIAL_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> compose() {
        try {
            String yaml = Files.readString(REPO_ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(1000);
            return new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (IOException e) {
            throw new IllegalStateException("the compose deployment cannot be read", e);
        }
    }

    private static Map<String, String> envValues(String file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(REPO_ROOT.resolve(file), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = ENV_ENTRY.matcher(trimmed);
            if (matcher.matches()) {
                values.put(matcher.group(1), matcher.group(2));
            }
        }
        return values;
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("utils"))
                    && Files.exists(current.resolve("docker/keycloak/import/ecommerce-realm.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("the repository root was not found");
    }
}
