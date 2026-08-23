package com.shop.common.core.viewmodel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Structured error payload returned alongside {@link ApiResponse#error} responses.
 *
 * @param code      stable machine-readable application code (e.g. {@code "AUTH-1001"})
 * @param message   human-readable, locale-aware summary
 * @param path      request URI that triggered the error
 * @param details   optional list of field-level / detail errors
 * @param traceId   correlation id propagated via MDC for distributed tracing
 * @param timestamp ISO-8601 instant the error was assembled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String path,
        List<String> details,
        String traceId,
        Instant timestamp
) {

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, path, null, null, Instant.now());
    }

    public static ErrorResponse of(String code, String message, List<String> details, String path) {
        return new ErrorResponse(code, message, path, details, null, Instant.now());
    }
}
