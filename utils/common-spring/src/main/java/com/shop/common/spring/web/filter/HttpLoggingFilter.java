package com.shop.common.spring.web.filter;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.spring.web.util.RequestUtils;
import com.shop.common.spring.web.util.TrustedProxyPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public class HttpLoggingFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> SKIP_PATHS = List.of(
            "/actuator/health/**", "/actuator/health",
            "/actuator/info", "/actuator/prometheus"
    );

    private final HttpLogProperties props;
    private final TrustedProxyPolicy trustedProxyPolicy;

    public HttpLoggingFilter(HttpLogProperties props) {
        this(props, TrustedProxyPolicy.none());
    }

    public HttpLoggingFilter(HttpLogProperties props, TrustedProxyPolicy trustedProxyPolicy) {
        this.props = props;
        this.trustedProxyPolicy = trustedProxyPolicy == null ? TrustedProxyPolicy.none() : trustedProxyPolicy;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return SKIP_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(
                request, props.getRequest().getMaxBodyBytes());
        BoundedContentCachingResponseWrapper res = new BoundedContentCachingResponseWrapper(
                response, props.getResponse().getMaxBufferBytes());

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            writeLine(req, res, System.currentTimeMillis() - start);
        }
    }

    private void writeLine(ContentCachingRequestWrapper req,
                           BoundedContentCachingResponseWrapper res,
                           long durationMs) {
        int status = res.getStatus();
        String ip = RequestUtils.getClientIp(req, trustedProxyPolicy);
        String corrId = MDC.get(MdcKey.CORRELATION_ID);
        String ua = req.getHeader("User-Agent");
        String referer = req.getHeader("Referer");

        StringBuilder sb = new StringBuilder()
                .append(buildPath(req))
                .append(" | ").append(req.getMethod())
                .append(" | ").append(status)
                .append(" | ").append(durationMs).append("ms")
                .append(" | ip=").append(ip)
                .append(" | ua=").append(ua != null ? ua : "-")
                .append(" | corrId=").append(corrId);

        if (referer != null && !referer.isBlank()) {
            sb.append(" | referer=").append(referer);
        }

        if (props.getRequest().isEnabled() && props.getRequest().isIncludeBody()) {
            appendBody(sb, "req", req.getContentAsByteArray(), props.getRequest().getMaxBodyBytes());
        }
        if (props.getResponse().isEnabled() && props.getResponse().isIncludeBody()) {
            appendBody(sb, "res", res.getContentAsByteArray(), props.getResponse().getMaxBodyBytes());
        }
        if (res.isTruncated()) {
            sb.append(" | resBuffer=truncated");
        }

        String line = sb.toString();
        if (status >= 500) {
            log.error(line);
        } else if (status >= 400) {
            log.warn(line);
        } else {
            log.info(line);
        }
    }

    private static String buildPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        return query != null ? path + "?" + query : path;
    }

    private static final Pattern SENSITIVE_JSON_PATTERN = Pattern.compile(
            "\"([^\"]*(?:password|secret|token|credential|authorization|cardNumber|cvv|accessToken)[^\"]*)\"\\s*:\\s*(\"[^\"]*\"|[^,\\}\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private static String maskBody(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return SENSITIVE_JSON_PATTERN.matcher(content).replaceAll("\"$1\":\"***\"");
    }

    private static void appendBody(StringBuilder sb, String label, byte[] body, int maxBytes) {
        if (body == null || body.length == 0) return;
        int len = Math.min(body.length, maxBytes);
        String content = new String(body, 0, len, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        content = maskBody(content);
        sb.append(" | ").append(label).append("=").append(content);
        if (body.length > maxBytes) {
            sb.append("...[truncated]");
        }
    }
}