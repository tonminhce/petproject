package com.shop.common.core.constants;

/**
 * Standard HTTP header names used by the platform.
 */
public final class HeaderConstants {

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String USER_ID = "X-User-Id";

    public static final String CONTENT_TYPE_JSON = "application/json";

    private HeaderConstants() {
    }
}
