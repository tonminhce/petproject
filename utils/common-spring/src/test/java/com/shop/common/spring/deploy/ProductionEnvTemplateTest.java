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

class ProductionEnvTemplateTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)([:?-][^}]*)?}");

    private static final Path BASE_COMPOSE = Path.of("docker-compose.yml");
    private static final Path PROD_OVERLAY = Path.of("docker-compose.prod.yml");
    private static final Path TEMPLATE = Path.of(".env.prod.example");

    @Test
    @DisplayName("every variable the production stack requires is present in the template")
    void everyRequiredVariableIsPresentInTheTemplate() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        Set<String> required = new LinkedHashSet<>();
        for (Path compose : List.of(BASE_COMPOSE, PROD_OVERLAY)) {
            Path file = repoRoot.resolve(compose);
            Assumptions.assumeTrue(Files.exists(file), compose + " not found - contract not checked");
            required.addAll(requiredVariables(Files.readString(file, StandardCharsets.UTF_8)));
        }

        String template = Files.readString(repoRoot.resolve(TEMPLATE), StandardCharsets.UTF_8);
        Set<String> declared = declaredVariables(template);

        List<String> missing = new ArrayList<>();
        for (String variable : required) {
            if (!declared.contains(variable)) {
                missing.add(variable);
            }
        }

        assertThat(required)
                .as("the scan must actually find the production stack's required variables")
                .isNotEmpty();
        assertThat(missing)
                .as("every variable the production stack requires must be documented in .env.prod.example")
                .isEmpty();
    }

    @Test
    @DisplayName("the gateway CORS value in the template is an explicit non-wildcard value")
    void theGatewayCorsValueIsNotAWildcard() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        String template = Files.readString(repoRoot.resolve(TEMPLATE), StandardCharsets.UTF_8);
        String value = valueOf(template, "CORS_ALLOWED_ORIGIN_PATTERNS");

        assertThat(value)
                .as("the gateway refuses a wildcard CORS pattern when credentials are enabled")
                .isNotBlank()
                .doesNotContain("*");
    }

    private static Set<String> requiredVariables(String compose) {
        Set<String> required = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(compose);
        while (matcher.find()) {
            String suffix = matcher.group(2) == null ? "" : matcher.group(2);
            boolean optional = suffix.startsWith(":-")
                    || (suffix.startsWith("-") && !suffix.startsWith("-?"));
            if (!optional) {
                required.add(matcher.group(1));
            }
        }
        return required;
    }

    private static Set<String> declaredVariables(String template) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("^\\s*#?\\s*([A-Z0-9_]+)\\s*=", Pattern.MULTILINE).matcher(template);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    private static String valueOf(String template, String variable) {
        Matcher matcher = Pattern.compile("^\\s*#?\\s*" + Pattern.quote(variable) + "\\s*=\\s*(.*)$",
                Pattern.MULTILINE).matcher(template);
        return matcher.find() ? matcher.group(1).trim() : "";
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
