package com.shop.common.spring.web.filter;

import com.shop.common.core.constants.MdcKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    static final int MAX_IDENTIFIER_LENGTH = 64;
    static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String correlationId = validatedIdentifierOrNew(request, CORRELATION_ID_HEADER);
        String requestId = validatedIdentifierOrNew(request, REQUEST_ID_HEADER);

        MDC.put(MdcKey.CORRELATION_ID, correlationId);
        MDC.put(MdcKey.REQUEST_ID, requestId);

        if (MDC.get(MdcKey.TRACE_ID) == null) {
            MDC.put(MdcKey.TRACE_ID, correlationId);
        }

        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKey.CORRELATION_ID);
            MDC.remove(MdcKey.REQUEST_ID);
            MDC.remove(MdcKey.TRACE_ID);
        }
    }

    static String validatedIdentifierOrNew(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value != null && SAFE_IDENTIFIER.matcher(value).matches()) {
            return value;
        }
        return UUID.randomUUID().toString();
    }
}
