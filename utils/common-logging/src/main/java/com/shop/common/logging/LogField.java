package com.shop.common.logging;

public record LogField(
        String corrId,
        String service,
        String action,
        String title,
        long durationMs,
        String result,
        String inputs,
        String output
) {
    public static final String SUCCESS = "SUCCESS";
    public static final String ERROR = "ERROR";
}
