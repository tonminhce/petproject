package com.shop.common.spring.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tooling that runs beside the platform must not ship a credential.
 *
 * <p>SonarQube holds the source of every service and is reachable on a
 * published port. A literal password in the compose file or in the setup
 * script is a credential that everyone who clones the repository knows, and
 * a "rotation" to another literal is not a rotation at all.</p>
 */
class ToolingCredentialDefaultsTest {

    private static final Pattern COMPOSE_SECRET = Pattern.compile(
            "(?i)^\\s*(POSTGRES_PASSWORD|SONAR_JDBC_PASSWORD|SONAR_PASSWORD)\\s*:\\s*(.+?)\\s*$");

    private static final Pattern SHELL_DEFAULT = Pattern.compile(
            "\\$\\{([A-Z0-9_]*(?:PASS|PASSWORD|SECRET|TOKEN)[A-Z0-9_]*):-([^}]*)}");

    @Test
    @DisplayName("the SonarQube compose file takes its database password from the environment")
    void theSonarComposeFileTakesItsDatabasePasswordFromTheEnvironment() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path compose = repoRoot.resolve("docker-compose.sonar.yml");
        Assumptions.assumeTrue(Files.exists(compose), "docker-compose.sonar.yml not found - contract not checked");

        String content = Files.readString(compose, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        for (String line : content.split("\n")) {
            Matcher matcher = COMPOSE_SECRET.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String value = matcher.group(2);
            if (value.startsWith("${") && value.contains(":?")) {
                continue;
            }
            offenders.add(matcher.group(1) + " -> " + value);
        }

        assertThat(offenders)
                .as("a literal database password in the SonarQube compose file is a published credential")
                .isEmpty();
    }

    @Test
    @DisplayName("the SonarQube setup script ships no password default")
    void theSonarSetupScriptShipsNoPasswordDefault() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path script = repoRoot.resolve("scripts/setup-sonar-project.sh");
        Assumptions.assumeTrue(Files.exists(script), "scripts/setup-sonar-project.sh not found - contract not checked");

        String content = Files.readString(script, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        Matcher matcher = SHELL_DEFAULT.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            if (!value.isEmpty()) {
                offenders.add(matcher.group(1) + " -> " + value);
            }
        }

        assertThat(offenders)
                .as("a password default in the setup script is a credential published in the repository")
                .isEmpty();
    }

    @Test
    @DisplayName("the SonarQube setup script refuses to run without both passwords")
    void theSonarSetupScriptRefusesToRunWithoutBothPasswords() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path script = repoRoot.resolve("scripts/setup-sonar-project.sh");
        Assumptions.assumeTrue(Files.exists(script), "scripts/setup-sonar-project.sh not found - contract not checked");

        String content = Files.readString(script, StandardCharsets.UTF_8);

        assertThat(content)
                .as("the script must fail fast instead of applying a default password")
                .contains("SONAR_ADMIN_PASS and SONAR_NEW_ADMIN_PASS must both be set")
                .contains("exit 1");
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
