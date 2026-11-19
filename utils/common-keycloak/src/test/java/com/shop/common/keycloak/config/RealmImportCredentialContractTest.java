package com.shop.common.keycloak.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * F-080: the realm import must not carry a credential that the deployment
 * cannot rotate.
 *
 * <p>The realm bootstrap script substitutes {@code ${VAR}} placeholders from
 * the environment before Keycloak reads the realm JSON, and it refuses to
 * boot when a required variable is missing. The placeholders were once
 * reverted to the literal {@code changeme} while the script kept requiring
 * the variables: the substitution had nothing to replace, so the realm booted
 * with the well-known secrets while the deployment believed it had rotated
 * them. The seeded ADMIN user carried the literal {@code adminpass} with no
 * placeholder at all.</p>
 *
 * <p>Two contracts are asserted here:</p>
 * <ul>
 *   <li>every credential in the realm JSON is a {@code ${VAR}} placeholder -
 *       never a literal;</li>
 *   <li>every placeholder is a variable the bootstrap script actually
 *       substitutes - a placeholder nobody replaces would be a literal in
 *       disguise.</li>
 * </ul>
 */
class RealmImportCredentialContractTest {

    private static final Path REPO_ROOT = repoRoot();
    private static final Path REALM_FILE = REPO_ROOT.resolve("docker/keycloak/import/ecommerce-realm.json");
    private static final Path ENTRYPOINT = REPO_ROOT.resolve("docker/keycloak/import/entrypoint.sh");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

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

    private JsonNode realm() throws IOException {
        return new ObjectMapper().readTree(Files.readString(REALM_FILE));
    }

    private Set<String> substitutedVariables() throws IOException {
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(Files.readString(ENTRYPOINT));
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        // The script builds the pattern with a shell expansion - ${$v} - so the
        // names also appear inside the loop. The names of the loop variables
        // are read from the same file.
        String script = Files.readString(ENTRYPOINT);
        Matcher loop = Pattern.compile("for v in ([A-Z0-9_ ]+); do").matcher(script);
        while (loop.find()) {
            for (String name : loop.group(1).split("\\s+")) {
                if (!name.isBlank()) {
                    variables.add(name);
                }
            }
        }
        return variables;
    }

    @Test
    @DisplayName("no client of the realm carries a literal secret")
    void noClientOfTheRealmCarriesALiteralSecret() throws IOException {
        List<String> literals = new ArrayList<>();
        for (JsonNode client : realm().path("clients")) {
            JsonNode secret = client.path("secret");
            if (!secret.isMissingNode() && !secret.isNull() && !secret.asText().isBlank()) {
                String value = secret.asText();
                if (!PLACEHOLDER.matcher(value).matches()) {
                    literals.add(client.path("clientId").asText() + " -> " + value);
                }
            }
        }

        assertThat(literals)
                .as("a literal client secret cannot be rotated - the bootstrap script can only replace a placeholder")
                .isEmpty();
    }

    @Test
    @DisplayName("no seeded user of the realm carries a literal password")
    void noSeededUserOfTheRealmCarriesALiteralPassword() throws IOException {
        List<String> literals = new ArrayList<>();
        for (JsonNode user : realm().path("users")) {
            for (JsonNode credential : user.path("credentials")) {
                if ("password".equals(credential.path("type").asText())) {
                    String value = credential.path("value").asText();
                    if (!value.isBlank() && !PLACEHOLDER.matcher(value).matches()) {
                        literals.add(user.path("username").asText() + " -> " + value);
                    }
                }
            }
        }

        assertThat(literals)
                .as("a literal user password cannot be rotated - the bootstrap script can only replace a placeholder")
                .isEmpty();
    }

    @Test
    @DisplayName("every placeholder of the realm is substituted by the bootstrap script")
    void everyPlaceholderOfTheRealmIsSubstitutedByTheBootstrapScript() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(Files.readString(REALM_FILE));
        while (matcher.find()) {
            used.add(matcher.group(1));
        }

        Set<String> substituted = substitutedVariables();

        List<String> missing = new ArrayList<>();
        for (String variable : used) {
            if (!substituted.contains(variable)) {
                missing.add(variable);
            }
        }

        assertThat(used).as("the realm must use placeholders").isNotEmpty();
        assertThat(missing)
                .as("a placeholder that nobody substitutes is a literal in disguise")
                .isEmpty();
    }

    @Test
    @DisplayName("the bootstrap script refuses to boot when a required variable is missing")
    void theBootstrapScriptRefusesToBootWhenARequiredVariableIsMissing() throws IOException {
        String script = Files.readString(ENTRYPOINT);

        // The fail-fast loop must run BEFORE the substitution and must cover
        // every placeholder the realm uses.
        int failFast = script.indexOf("is not set; refusing to boot");
        int substitute = script.indexOf("sed -i");
        assertThat(failFast).as("the fail-fast guard must exist").isPositive();
        assertThat(substitute).as("the substitution loop must exist").isPositive();
        assertThat(failFast).as("the fail-fast guard must run before the substitution").isLessThan(substitute);
        assertThat(script).contains("exit 1");
    }

    /**
     * F-081: the bootstrap script must never write the substituted realm back
     * into the mounted template directory.
     *
     * <p>The template is bind-mounted from the repository. Writing the
     * substituted realm back into it persisted the rotated secrets into the
     * developer's working tree, ready to be committed. The substituted copy
     * belongs in the container-local directory Keycloak imports from.</p>
     */
    @Test
    @DisplayName("the bootstrap script never writes the substituted realm back into the mounted template")
    void theBootstrapScriptNeverWritesBackIntoTheMountedTemplate() throws IOException {
        String script = Files.readString(ENTRYPOINT);

        // The source the script reads must be the read-only template mount.
        assertThat(script)
                .as("the script must read from the read-only template mount")
                .contains("/opt/keycloak/realm-template/ecommerce-realm.json");

        // The script must not copy anything INTO the template directory.
        assertThat(script)
                .as("a write-back into the template would persist the rotated secrets in the repository")
                .doesNotContain("cp \"$tmp\" /opt/keycloak/realm-template");
        assertThat(script)
                .as("a write-back into the template would persist the rotated secrets in the repository")
                .doesNotContain("cp \"$tmp\" \"$src\"");
        assertThat(script)
                .as("a write-back into the template would persist the rotated secrets in the repository")
                .doesNotContain("> /opt/keycloak/realm-template");
        assertThat(script)
                .as("a write-back into the template would persist the rotated secrets in the repository")
                .doesNotContain("sed -i.*/opt/keycloak/realm-template");

        // The substituted realm must land in the container-local import dir.
        assertThat(script)
                .as("the substituted realm must land in the directory Keycloak imports from")
                .contains("/opt/keycloak/data/import");
    }

    /**
     * F-081: the compose deployment must mount the template READ-ONLY.
     *
     * <p>A read-write mount is what allowed the write-back in the first
     * place. The compose file is the contract the deployment ships with.</p>
     */
    @Test
    @DisplayName("the compose deployment mounts the realm template read-only")
    void theComposeDeploymentMountsTheRealmTemplateReadOnly() throws IOException {
        String compose = Files.readString(REPO_ROOT.resolve("docker-compose.yml"));

        assertThat(compose)
                .as("the template must be mounted read-only so the container cannot rewrite the repository copy")
                .contains("./docker/keycloak/import:/opt/keycloak/realm-template:ro");
        assertThat(compose)
                .as("the template must not be mounted into the import directory any more")
                .doesNotContain("./docker/keycloak/import:/opt/keycloak/data/import");
        assertThat(compose)
                .as("the entrypoint must be invoked from the template mount")
                .contains("/opt/keycloak/realm-template/entrypoint.sh");
    }

    /**
     * F-081: the bootstrap script must be a POSIX shell script with LF line
     * endings.
     *
     * <p>The template is bind-mounted into the container and executed by
     * {@code sh}. With CRLF line endings the shell reads the carriage return
     * as part of the command, so the script dies with "set: -r: invalid
     * option" before it substitutes anything and the whole compose stack
     * fails to boot Keycloak. {@code core.autocrlf=true} produced exactly
     * that on a Windows checkout, so {@code .gitattributes} pins the
     * line endings.</p>
     */
    @Test
    @DisplayName("the bootstrap script has LF line endings so the container can execute it")
    void theBootstrapScriptHasLfLineEndings() throws IOException {
        byte[] bytes = Files.readAllBytes(ENTRYPOINT);
        int crlf = 0;
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                crlf++;
            }
        }
        assertThat(crlf)
                .as("CRLF line endings break the containerised bootstrap script")
                .isZero();
    }

    /**
     * F-081: {@code .gitattributes} must pin the shell scripts to LF so a
     * checkout on any platform keeps the bootstrap script executable.
     */
    @Test
    @DisplayName("the repository pins the shell scripts to LF line endings")
    void theRepositoryPinsTheShellScriptsToLf() throws IOException {
        Path attributes = REPO_ROOT.resolve(".gitattributes");
        assertThat(attributes)
                .as(".gitattributes must exist so core.autocrlf cannot break the bootstrap script")
                .exists();
        assertThat(Files.readString(attributes))
                .as("the shell scripts must be checked out with LF")
                .contains("*.sh text eol=lf");
    }
}
