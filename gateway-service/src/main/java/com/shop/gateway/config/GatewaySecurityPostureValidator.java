package com.shop.gateway.config;

import com.shop.gateway.filter.AdminIpAllowlistProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurityPostureValidator {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityPostureValidator.class);

    private final GatewayProperties gatewayProperties;
    private final TrustedProxyProperties trustedProxyProperties;
    private final AdminIpAllowlistProperties adminIpAllowlistProperties;
    private final String forwardHeadersStrategy;
    private final boolean requireAdminIpAllowlist;

    public GatewaySecurityPostureValidator(
            GatewayProperties gatewayProperties,
            TrustedProxyProperties trustedProxyProperties,
            AdminIpAllowlistProperties adminIpAllowlistProperties,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy,
            @Value("${gateway.admin-ip-allowlist.required:false}") boolean requireAdminIpAllowlist) {
        this.gatewayProperties = gatewayProperties;
        this.trustedProxyProperties = trustedProxyProperties;
        this.adminIpAllowlistProperties = adminIpAllowlistProperties;
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        this.requireAdminIpAllowlist = requireAdminIpAllowlist;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void validate() {
        validateCors();
        validateClientIpTrust();
        validateAdminIpAllowlist();
    }

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

    private void validateAdminIpAllowlist() {
        if (adminIpAllowlistProperties.active()) {
            log.info("Admin IP allowlist is enforcing with {} CIDR entr(y|ies)",
                    adminIpAllowlistProperties.cidrs().size());
            return;
        }
        if (requireAdminIpAllowlist) {
            throw new IllegalStateException(
                    "ADMIN_IP_ALLOWLIST is empty while gateway.admin-ip-allowlist.required=true. "
                    + "The backoffice would be reachable from any network origin. Set "
                    + "ADMIN_IP_ALLOWLIST to the operator CIDRs (comma separated) or turn the "
                    + "requirement off explicitly.");
        }
        log.warn("Admin IP allowlist is INACTIVE: the backoffice is reachable from any network origin "
                + "and only the JWT role check protects it. Set ADMIN_IP_ALLOWLIST to the operator "
                + "CIDRs (comma separated) for any deployment that is not a local sandbox.");
    }

    private void validateClientIpTrust() {
        String strategy = forwardHeadersStrategy == null ? "none" : forwardHeadersStrategy.trim();
        if (!"none".equalsIgnoreCase(strategy)) {
            throw new IllegalStateException(
                    "server.forward-headers-strategy is '" + strategy + "'. The gateway must resolve the "
                    + "client address from the transport peer, so the framework must not rewrite the "
                    + "remote address from a caller-controlled X-Forwarded-For header. Set "
                    + "server.forward-headers-strategy=none and declare the trusted proxies with "
                    + "gateway.trusted-proxy.cidrs and gateway.trusted-proxy.hops.");
        }
        final int hops = trustedProxyProperties.hops();
        final List<String> cidrs = trustedProxyProperties.cidrs();
        if (hops > 0 && cidrs.isEmpty()) {
            throw new IllegalStateException(
                    "gateway.trusted-proxy.hops is " + hops + " while gateway.trusted-proxy.cidrs is empty. "
                    + "The gateway would trust the X-Forwarded-For header of any peer. Set "
                    + "gateway.trusted-proxy.cidrs to the CIDRs of the reverse proxies or set "
                    + "gateway.trusted-proxy.hops=0 to meter the transport peer only.");
        }
        if (hops > 0) {
            log.info("Gateway client-IP trust: trusted-proxy CIDR(s)={} trusted-proxy hops={}", cidrs.size(), hops);
            return;
        }
        log.warn("Gateway client-IP trust: X-Forwarded-For is IGNORED, every client is metered and "
                + "allow-listed by its transport peer address. Set gateway.trusted-proxy.cidrs and "
                + "gateway.trusted-proxy.hops when the gateway runs behind a reverse proxy that "
                + "overwrites X-Forwarded-For, otherwise all clients share the address of the proxy.");
    }
}
