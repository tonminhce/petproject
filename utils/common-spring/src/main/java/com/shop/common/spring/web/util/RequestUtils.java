package com.shop.common.spring.web.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestUtils {

    private RequestUtils() {}

    public static String getClientIp(HttpServletRequest request) {
        return getClientIp(request, TrustedProxyPolicy.none());
    }

    public static String getClientIp(HttpServletRequest request, TrustedProxyPolicy trustedProxyPolicy) {
        if (trustedProxyPolicy == null) {
            return TrustedProxyPolicy.none().resolveClientIp(request);
        }
        return trustedProxyPolicy.resolveClientIp(request);
    }

    public static boolean isLocalAddress(String ip) {
        return "127.0.0.1".equals(ip) || "localhost".equals(ip);
    }
}
