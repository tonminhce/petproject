package com.shop.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realm-import acceptance IT (production-readiness task 8): boots the SAME
 * Keycloak image/compose command ({@code quay.io/keycloak/keycloak:26.0},
 * {@code start-dev --import-realm}) in a FRESH container (no persistent volume —
 * {@code --import-realm} skips import when the realm already exists), mounts the
 * ACTUAL {@code docker/keycloak/import/ecommerce-realm.json} from the repo, and
 * proves end-to-end that the import renders AND the service-account grants work:
 * the 5 confidential clients exist with service accounts enabled, each obtains a
 * client_credentials token whose JWT claims identify the service account
 * (preferred_username = service-account-&lt;clientId&gt;, sub = that service
 * account's user id) AND carries the SERVICE realm role (final-review F2 —
 * without it every SERVICE-gated internal endpoint 403s machine callers), and
 * the public frontend client is DENIED that grant.
 *
 * <p>Client ids/secrets are parsed from the mounted JSON file — nothing is
 * duplicated here, so a realm-config drift fails this IT.</p>
 *
 * <p>SINGLETON LIFECYCLE — fleet pattern (see order-service
 * {@code AbstractOrderServiceIT}): containers start once per JVM in a static
 * initializer and are never stopped; the surefire fork's exit (plus Ryuk) reaps
 * them. Docker required — plain {@code mvn test} runs this class via the module's
 * surefire {@code *IT.java} include (order/search convention), so CI-less
 * environments without Docker surface a clean container-start failure rather than
 * a silent skip.</p>
 */
class KeycloakRealmImportIT {

    private static final String IMAGE = "quay.io/keycloak/keycloak:26.0";
    private static final String REALM = "ecommerce";
    private static final String IMPORT_CONTAINER_PATH = "/opt/keycloak/data/import/ecommerce-realm.json";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** The real realm file, located above the module dir (works from repo root or module cwd). */
    private static final Path REALM_FILE = locateRealmImportFile();
    private static final JsonNode REALM_JSON = parseUnchecked(readFile(REALM_FILE));
    private static final List<String> CONFIDENTIAL_CLIENT_IDS = confidentialClientIds(REALM_JSON);
    private static final String PUBLIC_CLIENT_ID = publicClientId(REALM_JSON);

    @SuppressWarnings("resource")
    static final GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse(IMAGE))
        .withExposedPorts(8080)
        // Same command as the compose keycloak service minus --hostname (that pins
        // iss to the internal DNS name for inter-container calls; a mapped-port test
        // talks to Keycloak the way an external client does, on the default host).
        .withCommand("start-dev", "--http-port=8080", "--import-realm")
        // KC 26 bootstrap admin (compose uses the legacy KEYCLOAK_* aliases of these).
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
        .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), IMPORT_CONTAINER_PATH)
        // The realm endpoint answering 200 IS the import-applied proof (Keycloak 26
        // import concern from task 1 review): a fresh container only serves
        // /realms/ecommerce after --import-realm rendered the file.
        .waitingFor(Wait.forHttp("/realms/" + REALM).forStatusCode(200));

    static {
        keycloak.withStartupTimeout(Duration.ofMinutes(3));
        keycloak.start();
    }

    private static String adminToken() {
        Response r = postForm("/realms/master/protocol/openid-connect/token",
            "grant_type", "password",
            "client_id", "admin-cli",
            "username", ADMIN_USER,
            "password", ADMIN_PASSWORD);
        assertThat(r.status()).as("admin-cli password grant").isEqualTo(200);
        return r.json().path("access_token").asText();
    }

    @Test
    void realmImport_realmExistsAndEnabled() throws Exception {
        Response discovery = send(HttpRequest.newBuilder(
                URI.create(base() + "/realms/" + REALM + "/.well-known/openid-configuration")).GET().build());
        assertThat(discovery.status()).as("OIDC discovery").isEqualTo(200);
        assertThat(discovery.json().path("issuer").asText()).endsWith("/realms/" + REALM);

        Response realm = send(HttpRequest.newBuilder(URI.create(base() + "/admin/realms/" + REALM))
            .header("Authorization", "Bearer " + adminToken()).GET().build());
        assertThat(realm.status()).as("realm via admin API").isEqualTo(200);
        assertThat(realm.json().path("realm").asText()).isEqualTo(REALM);
        assertThat(realm.json().path("enabled").asBoolean()).isTrue();
    }

    @ParameterizedTest(name = "{0} renders with service account enabled")
    @MethodSource("confidentialClients")
    void realmImport_confidentialClientsRenderWithServiceAccounts(String clientId) {
        Response r = send(HttpRequest.newBuilder(
                URI.create(base() + "/admin/realms/" + REALM + "/clients?clientId=" + clientId))
            .header("Authorization", "Bearer " + adminToken()).GET().build());
        assertThat(r.status()).isEqualTo(200);
        JsonNode matches = r.json();
        assertThat(matches.size()).as("client %s exists post-import", clientId).isEqualTo(1);
        assertThat(matches.get(0).path("serviceAccountsEnabled").asBoolean())
            .as("client %s serviceAccountsEnabled", clientId).isTrue();
        assertThat(matches.get(0).path("publicClient").asBoolean())
            .as("client %s confidential", clientId).isFalse();
        // Secret as rendered by the import equals the secret shipped in the JSON —
        // the value the compose envs (changeme) rely on.
        assertThat(matches.get(0).path("secret").asText())
            .as("client %s imported secret", clientId)
            .isEqualTo(clientNodes(REALM_JSON).stream()
                .filter(c -> clientId.equals(c.path("clientId").asText()))
                .findFirst().orElseThrow().path("secret").asText());
    }

    @ParameterizedTest(name = "{0} obtains a client_credentials service-account token")
    @MethodSource("confidentialClients")
    void clientCredentialsGrant_issuesServiceAccountToken(String clientId) {
        String secret = clientNodes(REALM_JSON).stream()
            .filter(c -> clientId.equals(c.path("clientId").asText()))
            .findFirst().orElseThrow().path("secret").asText();
        Response r = postForm("/realms/" + REALM + "/protocol/openid-connect/token",
            "grant_type", "client_credentials",
            "client_id", clientId,
            "client_secret", secret);
        assertThat(r.status()).as("token endpoint status for %s", clientId).isEqualTo(200);
        String token = r.json().path("access_token").asText();
        assertThat(token).as("access_token issued").isNotBlank();

        JsonNode claims = jwtClaims(token);
        // KC 26: sub is the service-account USER's id (UUID); the service-account-
        // <clientId> identity lives in preferred_username. Prove sub ownership by
        // matching it against the client's actual service-account user via admin API.
        assertThat(claims.path("preferred_username").asText()).isEqualTo("service-account-" + clientId);
        assertThat(claims.path("sub").asText()).isNotBlank();
        Response saUser = send(HttpRequest.newBuilder(URI.create(
                base() + "/admin/realms/" + REALM + "/clients/"
                    + clientIdOf(clientId) + "/service-account-user"))
            .header("Authorization", "Bearer " + adminToken()).GET().build());
        assertThat(saUser.status()).as("service-account user of %s", clientId).isEqualTo(200);
        assertThat(saUser.json().path("username").asText()).isEqualTo("service-account-" + clientId);
        assertThat(claims.path("sub").asText())
            .as("sub = %s service-account user id", clientId)
            .isEqualTo(saUser.json().path("id").asText());
        assertThat(claims.path("iss").asText()).endsWith("/realms/" + REALM);
        assertThat(claims.has("email")).as("service account carries no email").isFalse();
    }

    @ParameterizedTest(name = "{0} service-account token carries the SERVICE realm role")
    @MethodSource("confidentialClients")
    void clientCredentialsGrant_tokenCarriesServiceRealmRole(String clientId) {
        String secret = clientNodes(REALM_JSON).stream()
            .filter(c -> clientId.equals(c.path("clientId").asText()))
            .findFirst().orElseThrow().path("secret").asText();
        Response r = postForm("/realms/" + REALM + "/protocol/openid-connect/token",
            "grant_type", "client_credentials",
            "client_id", clientId,
            "client_secret", secret);
        assertThat(r.status()).as("token endpoint status for %s", clientId).isEqualTo(200);

        JsonNode claims = jwtClaims(r.json().path("access_token").asText());
        List<String> realmRoles = new ArrayList<>();
        claims.path("realm_access").path("roles").forEach(role -> realmRoles.add(role.asText()));
        assertThat(realmRoles)
            .as("realm_access.roles of %s machine token (F2: SERVICE-gated internal calls)", clientId)
            .contains("SERVICE");
        assertThat(realmRoles)
            .as("machine token of %s must not carry the ADMIN realm role", clientId)
            .doesNotContain("ADMIN");
    }

    @Test
    void publicClient_clientCredentialsGrantDenied() {
        Response r = postForm("/realms/" + REALM + "/protocol/openid-connect/token",
            "grant_type", "client_credentials",
            "client_id", PUBLIC_CLIENT_ID);
        assertThat(r.status())
            .as("public client %s must not use client_credentials", PUBLIC_CLIENT_ID)
            .isIn(400, 401);
        assertThat(r.json().path("error").asText())
            .as("OAuth error code present").isNotBlank();
    }

    static Stream<String> confidentialClients() {
        return CONFIDENTIAL_CLIENT_IDS.stream();
    }

    // ----- helpers -----

    /** Internal UUID of an imported client, via the admin API. */
    private static String clientIdOf(String clientId) {
        Response r = send(HttpRequest.newBuilder(
                URI.create(base() + "/admin/realms/" + REALM + "/clients?clientId=" + clientId))
            .header("Authorization", "Bearer " + adminToken()).GET().build());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().size()).as("client %s exists", clientId).isEqualTo(1);
        return r.json().get(0).path("id").asText();
    }

    private record Response(int status, JsonNode json) {
    }

    private static String base() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    private static Response postForm(String path, String... kv) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(kv[i])).append('=').append(encode(kv[i + 1]));
        }
        return send(HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build());
    }

    private static String encode(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
            return new Response(resp.statusCode(), MAPPER.readTree(body));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        }
    }

    private static JsonNode jwtClaims(String token) {
        String[] parts = token.split("\\.");
        assertThat(parts).as("JWT has 3 segments").hasSize(3);
        try {
            return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new IllegalStateException("JWT payload decode failed", e);
        }
    }

    private static List<JsonNode> clientNodes(JsonNode realm) {
        List<JsonNode> clients = new ArrayList<>();
        realm.path("clients").forEach(clients::add);
        return clients;
    }

    private static List<String> confidentialClientIds(JsonNode realm) {
        List<String> ids = new ArrayList<>();
        clientNodes(realm).forEach(c -> {
            if (c.hasNonNull("secret")) {
                ids.add(c.path("clientId").asText());
            }
        });
        assertThat(ids).as("realm JSON declares the 5 confidential clients").hasSize(5);
        return ids;
    }

    private static String publicClientId(JsonNode realm) {
        return clientNodes(realm).stream()
            .filter(c -> c.path("publicClient").asBoolean(false))
            .map(c -> c.path("clientId").asText())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no public client in realm JSON"));
    }

    private static Path locateRealmImportFile() {
        for (Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
             dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docker/keycloak/import/ecommerce-realm.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "docker/keycloak/import/ecommerce-realm.json not found above " + System.getProperty("user.dir"));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    private static JsonNode parseUnchecked(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse realm JSON", e);
        }
    }
}
