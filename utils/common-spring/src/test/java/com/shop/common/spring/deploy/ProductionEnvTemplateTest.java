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
 * F-036: the production environment template must cover every variable the
 * production stack REQUIRES.
 *
 * <p>The compose stack resolves a variable in three shapes:</p>
 * <ul>
 *   <li>{@code ${VAR}} / {@code ${VAR:?message}} - the operator MUST provide
 *       it; the stack refuses to start otherwise;</li>
 *   <li>{@code ${VAR:-default}} / {@code ${VAR-default}} - the stack falls
 *       back to a default;</li>
 *   <li>{@code ${VAR:?message}} with an empty value - also a startup
 *       failure.</li>
 * </ul>
 *
 * <p>{@code .env.prod.example} is the template the operator copies. A
 * variable that the stack demands but the template does not mention is a
 * deployment that fails at startup with a message about a variable the
 * operator was never told about - exactly what happened with
 * {@code CORS_ALLOWED_ORIGIN_PATTERNS}, which the gateway requires and the
 * template omitted.</p>
 */
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

    /** The variables a compose file demands (no default value). */
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

    /** The variable names the template declares (commented lines count). */
    private static Set<String> declaredVariables(String template) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("^\\s*#?\\s*([A-Z0-9_]+)\\s*=", Pattern.MULTILINE).matcher(template);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    /** The value the template declares for a variable (empty when it is absent). */
    private static String valueOf(String template, String variable) {
        Matcher matcher = Pattern.compile("^\\s*#?\\s*" + Pattern.quote(variable) + "\\s*=\\s*(.*)$",
                Pattern.MULTILINE).matcher(template);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** The reactor root: the directory that holds the modules and the parent pom. */
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
