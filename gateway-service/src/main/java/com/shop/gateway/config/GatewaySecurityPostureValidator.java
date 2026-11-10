package com.shop.gateway.config;

import com.shop.gateway.ratelimit.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Fail-fast guards for the gateway's two network-edge security postures.
 * <p>Both misconfigurations are silent at runtime: a wildcard CORS pattern
 * with credentials silently accepts every origin, and a zero trusted-proxy
 * hop count silently makes the client-IP trust model either spoofable
 * (behind the ForwardedHeaderTransformer) or collapsed onto the proxy's
 * address. Failing fast at startup turns a silent security regression into
 * a deployment error.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityPostureValidator {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityPostureValidator.class);

    private final GatewayProperties gatewayProperties;
    private final RateLimitProperties rateLimitProperties;
    private final String forwardHeadersStrategy;

    public GatewaySecurityPostureValidator(
            GatewayProperties gatewayProperties,
            RateLimitProperties rateLimitProperties,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy) {
        this.gatewayProperties = gatewayProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.forwardHeadersStrategy = forwardHeadersStrategy;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void validate() {
        validateCors();
        validateClientIpTrust();
    }

    /**
     * {@code allowCredentials(true)} combined with a wildcard origin
     * pattern makes every website able to issue credentialed cross-origin
     * requests to the gateway and read the responses. The gateway's CORS
     * configuration always sets allowCredentials(true), so a wildcard pattern
     * is always a misconfiguration.
     */
    private void validateCors() {
        var patterns = gatewayProperties.corsAllowedOriginPatterns();
        boolean wildcard = patterns.stream().anyMatch(p -> p != null && p.trim().equals("*"));
        if (wildcard) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGIN_PATTERNS contains '*' while the gateway enables "
                    + "allowCredentials(true). Any website could send credentialed cross-origin "
                    + "requests to the gateway and read the responses. Configure an explicit "
                    + "origin allow-list (for example http://localhost:3000).");
        }
        log.info("Gateway CORS origin allow-list resolved to {} entr(y|ies) (credentials enabled)", patterns.size());
    }

    /**
     * The client IP is resolved from the X-Forwarded-For chain using
     * {@code gateway.rate-limit.trusted-proxy-hops}. A hop count of 0 makes the
     * resolver fall back to the socket address. Behind a reverse proxy that is
     * the PROXY's address: every anonymous caller shares one rate-limit bucket
     * and the admin allowlist evaluates the proxy instead of the caller. When
     * the forwarded-header strategy is not 'none', Spring additionally rewrites
     * the remote address from the caller-supplied header, which makes the
     * fallback spoofable instead.
     */
    private void validateClientIpTrust() {
        int hops = rateLimitProperties.trustedProxyHops();
        String strategy = forwardHeadersStrategy == null ? "none" : forwardHeadersStrategy.trim();
        if (hops <= 0 && !"none".equalsIgnoreCase(strategy)) {
            throw new IllegalStateException(
                    "gateway.rate-limit.trusted-proxy-hops is " + hops + " while "
                    + "server.forward-headers-strategy is '" + strategy + "'. The gateway would "
                    + "resolve the client IP from a caller-controlled X-Forwarded-For header, which "
                    + "bypasses the admin IP allowlist and the per-IP rate limit. Set "
                    + "server.forward-headers-strategy=none and trusted-proxy-hops to the number of "
                    + "trusted proxies in front of the gateway.");
        }
        log.info("Gateway client-IP trust: strategy={} trusted-proxy-hops={}", strategy, hops);
    }
}
