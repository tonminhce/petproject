package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Writes the fleet {@link ApiResponse} error envelope from the gateway edge
 * (D4 rate-limit 429, D5 IP-block 403 and encoded-path 400 responses).
 *
 * <p><strong>Message i18n choice (documented):</strong> the gateway is the
 * edge — it does not run the per-request Locale resolution services use.
 * It answers with the EN-default messages, mirroring
 * {@code messages_en.properties} strings exactly so clients get a
 * deterministic copy-paste of the service-side default locale payload.
 * The stable {@code code} field is the real contract; EN prose is best-effort.</p>
 *
 * <p>{@code traceId} mirrors the servlet-side envelope behaviour: services
 * stamp the MDC trace id; at the edge the passthrough {@code X-Correlation-Id}
 * request header (if any) fills the same JSON field — omitted when absent.</p>
 */
public final class GatewayErrorResponseWriter {

    static final String RATE_LIMITED_MESSAGE_EN = "Too many requests. Please slow down.";
    static final String ACCESS_DENIED_MESSAGE_EN = "You do not have permission to access this resource.";
    static final String BAD_REQUEST_MESSAGE_EN = "Malformed request path.";

    private static final Map<ErrorCode, String> EN_MESSAGES = Map.of(
            ErrorCode.TOO_MANY_REQUESTS, RATE_LIMITED_MESSAGE_EN,
            ErrorCode.ACCESS_DENIED, ACCESS_DENIED_MESSAGE_EN,
            ErrorCode.BAD_REQUEST, BAD_REQUEST_MESSAGE_EN);

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseWriter(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(final ServerWebExchange exchange, final ErrorCode errorCode) {
        final var response = exchange.getResponse();
        response.setStatusCode(errorCode.getHttpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        final var body = new ApiResponse<>(
                false,
                errorCode.getCode(),
                message(errorCode),
                null,
                null,
                exchange.getRequest().getPath().value(),
                traceId(exchange),
                Instant.now());

        final byte[] json = serialize(body, errorCode, exchange);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json)));
    }

    private byte[] serialize(final ApiResponse<Object> body, final ErrorCode errorCode,
                             final ServerWebExchange exchange) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (final JacksonException e) {
            return fallbackJson(errorCode, exchange);
        }
    }

    private String message(final ErrorCode errorCode) {
        return EN_MESSAGES.getOrDefault(errorCode, errorCode.getCode());
    }

    private String traceId(final ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
    }

    private byte[] fallbackJson(final ErrorCode errorCode, final ServerWebExchange exchange) {
        return ("{\"success\":false,\"code\":\"" + errorCode.getCode()
                + "\",\"path\":\"" + exchange.getRequest().getPath().value() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
