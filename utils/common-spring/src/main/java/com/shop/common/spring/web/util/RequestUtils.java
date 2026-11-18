package com.shop.common.spring.web.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestUtils {

    private static final String UNKNOWN = "unknown";
    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Original-Forwarded-For",
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "X-Real-IP"
    };

    private RequestUtils() {}

    public static String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADER_CANDIDATES) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !UNKNOWN.equalsIgnoreCase(ip)) {
                String resolved = ip.contains(",") ? ip.split(",")[0].trim() : ip;
                return normalize(resolved);
            }
        }
        return normalize(request.getRemoteAddr());
    }

    private static String normalize(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        return ip;
    }

    public static boolean isLocalAddress(String ip) {
        return "127.0.0.1".equals(ip) || "localhost".equals(ip);
    }
}
