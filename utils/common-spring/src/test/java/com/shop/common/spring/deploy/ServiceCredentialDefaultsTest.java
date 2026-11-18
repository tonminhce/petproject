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
 * No shipped service configuration may carry a credential default.
 *
 * <p>A placeholder of the shape {@code ${VAR:changeme}} means a deployment
 * that forgets the environment variable authenticates with a credential that
 * is published in this repository. The realm import bootstraps the very same
 * literal into Keycloak, so the fallback is not merely a placeholder: it is a
 * working credential.</p>
 */
class ServiceCredentialDefaultsTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)}");

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)^\\s*(client-secret|admin-client-secret|admin-password|password|secret-key|access-key)\\s*:\\s*(.+?)\\s*$");

    @Test
    @DisplayName("no service ships a non-empty default for a credential")
    void noServiceShipsACredentialDefault() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        for (Path config : serviceConfigurations(repoRoot)) {
            scanned++;
            String content = Files.readString(config, StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.strip().startsWith("#")) {
                    continue;
                }
                Matcher key = SECRET_KEY.matcher(line);
                if (!key.matches()) {
                    continue;
                }
                Matcher placeholder = PLACEHOLDER.matcher(key.group(2));
                if (!placeholder.find()) {
                    continue;
                }
                String defaultValue = placeholder.group(2).trim();
                if (!defaultValue.isEmpty()) {
                    offenders.add(repoRoot.relativize(config) + " -> " + key.group(1)
                            + " defaults to '" + defaultValue + "'");
                }
            }
        }

        assertThat(scanned)
                .as("the scan must actually reach the service configurations")
                .isGreaterThan(0);
        assertThat(offenders)
                .as("a credential default turns a forgotten environment variable into a working credential")
                .isEmpty();
    }

    private static List<Path> serviceConfigurations(Path repoRoot) throws IOException {
        List<Path> configs = new ArrayList<>();
        try (var dirs = Files.list(repoRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path application = dir.resolve("src/main/resources/application.yml");
                if (Files.exists(application)) {
                    configs.add(application);
                }
            }
        }
        Path shared = repoRoot.resolve("utils/common-spring/src/main/resources/application.yml");
        if (Files.exists(shared)) {
            configs.add(shared);
        }
        return configs;
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
