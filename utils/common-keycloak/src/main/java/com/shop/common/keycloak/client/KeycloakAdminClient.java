package com.shop.common.keycloak.client;

import com.shop.common.keycloak.config.KeycloakProperties;
import com.shop.common.keycloak.exception.KeycloakOperationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Application-facing wrapper around the {@link Keycloak} admin client.
 *
 * <p>Adds two things on top of the SDK:</p>
 * <ul>
 *   <li><b>Lifecycle ownership.</b> The bean holds a single
 *       {@link Keycloak} instance for the lifetime of the application and
 *       closes it cleanly on shutdown via {@link #close()}.</li>
 *   <li><b>Error translation.</b> Calls that return a JAX-RS {@link Response}
 *       are wrapped in {@link KeycloakOperationException} when the status
 *       indicates failure, so callers can react with a single throw type
 *       instead of inspecting {@code Response.Status.Family}.</li>
 * </ul>
 *
 * <p>This class is intentionally thin. Higher-level domain services
 * ({@code UserService}, {@code RoleService}, {@code RealmService}) build on
 * top of it.</p>
 */
@Component
public class KeycloakAdminClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private final Keycloak keycloak;
    private final KeycloakProperties properties;

    public KeycloakAdminClient(KeycloakClientFactory factory, KeycloakProperties properties) {
        this.keycloak = factory.create(properties);
        this.properties = properties;
        log.info("Initialized Keycloak admin client for realm '{}' at {}",
                properties.realm(), properties.serverUrl());
    }

    /** The configured tenant realm — every operation targets this realm unless overridden. */
    public String realm() {
        return properties.realm();
    }

    /** Raw {@link Keycloak} SDK access for advanced callers. */
    public Keycloak keycloak() {
        return keycloak;
    }

    /**
     * Run a JAX-RS call that returns a {@link Response}, throw a typed
     * {@link KeycloakOperationException} when the status is &gt;= 400, and
     * always close the response before returning.
     */
    public <T> T execute(String operation, Function<Keycloak, Response> call,
                          ResponseReader<T> reader) {
        try (Response response = call.apply(keycloak)) {
            int status = response.getStatus();
            if (status >= 400) {
                String body = response.readEntity(String.class);
                throw new KeycloakOperationException(
                        operation,
                        toHttpStatus(status),
                        "Keycloak %s failed: HTTP %d — %s".formatted(operation, status, body));
            }
            if (reader == null) {
                return null;
            }
            return reader.read(response);
        }
    }

    /** {@link #execute(String, Function, ResponseReader)} for operations that return no body. */
    public void executeVoid(String operation, Function<Keycloak, Response> call) {
        execute(operation, call, null);
    }

    @Override
    public void close() {
        if (keycloak != null && !keycloak.isClosed()) {
            log.info("Closing Keycloak admin client for realm '{}'", properties.realm());
            keycloak.close();
        }
    }

    /**
     * Convert a JAX-RS status code to Spring's {@link HttpStatus}. Falls back to
     * {@code INTERNAL_SERVER_ERROR} for codes outside Spring's enum so the
     * global handler still has a meaningful status to render.
     */
    public static HttpStatus toHttpStatus(int statusCode) {
        HttpStatus mapped = HttpStatus.resolve(statusCode);
        return mapped == null ? HttpStatus.INTERNAL_SERVER_ERROR : mapped;
    }

    /** Functional callback that lets callers extract the entity from a successful response. */
    @FunctionalInterface
    public interface ResponseReader<T> {
        T read(Response response);
    }
}
