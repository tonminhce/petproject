package com.shop.common.core.viewmodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shop.common.core.constants.MdcKey;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        List<String> errors,
        String path,
        String traceId,
        Instant timestamp
) {

    public static final String SUCCESS_CODE = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return success(null, data);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return success(message, data);
    }

    public static ApiResponse<Void> message(String message) {
        return success(message, null);
    }

    public static ApiResponse<Void> error(String code, String message, String path) {
        return new ApiResponse<>(false, code, message, null, null, path, currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message, List<String> errors, String path) {
        return new ApiResponse<>(false, code, message, null, errors, path, currentTraceId(), Instant.now());
    }

    private static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, ApiResponse.SUCCESS_CODE, message, data, null, null, currentTraceId(), Instant.now());
    }

    private static String currentTraceId() {
        return MDC.get(MdcKey.TRACE_ID);
    }
}
