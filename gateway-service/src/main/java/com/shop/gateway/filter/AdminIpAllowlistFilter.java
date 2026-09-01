package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.routing.ApiPaths;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * D5 — denies every request whose client IP is outside {@code ADMIN_IP_ALLOWLIST},
 * expressed as comma-separated CIDRs. Because the env is absent in dev, the
 * filter starts INACTIVE and becomes a fail-closed deny-by-default control the
 * moment the variable carries content.
 *
 * <p>Bypass list (never IP-blocked — third-party callers and probes have no
 * office IP): payment/shipping webhook paths ({@code /api/v1/webhooks/**}) and
 * the actuator health endpoint (incl. liveness/readiness probes).</p>
 */
public final class AdminIpAllowlistFilter implements GlobalFilter, Ordered {

    private static final String WEBHOOK_BYPASS_PREFIX = ApiPaths.WEBHOOKS_PREFIX + "/";
    private static final String HEALTH_BYPASS_PATH = ApiPaths.ACTUATOR_HEALTH;
    private static final String HEALTH_BYPASS_PREFIX = ApiPaths.ACTUATOR_HEALTH + "/";

    private final AdminIpAllowlistProperties properties;
    private final List<IpSubnetFilterRule> rules;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final ClientIpResolver clientIpResolver;

    public AdminIpAllowlistFilter(final AdminIpAllowlistProperties properties,
                                  final GatewayErrorResponseWriter errorResponseWriter,
                                  final ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.rules = properties.cidrs().stream().map(AdminIpAllowlistFilter::parseCidr).toList();
        this.errorResponseWriter = errorResponseWriter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!properties.active() || isBypassed(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        final String clientIp = clientIpResolver.resolve(exchange);
        if (isAllowed(clientIp)) {
            return chain.filter(exchange);
        }
        return errorResponseWriter.write(exchange, ErrorCode.ACCESS_DENIED);
    }

    @Override
    public int getOrder() {
        return FilterOrder.ADMIN_IP_ALLOWLIST;
    }

    private boolean isBypassed(final String path) {
        return path.equals(HEALTH_BYPASS_PATH)
                || path.startsWith(HEALTH_BYPASS_PREFIX)
                || path.startsWith(WEBHOOK_BYPASS_PREFIX);
    }

    private boolean isAllowed(final String clientIp) {
        // Literal-IP guard: InetAddress.getByName would otherwise perform a
        // blocking DNS lookup on attacker-controlled X-Forwarded-For input.
        if (!isLiteralAddress(clientIp)) {
            return false;
        }
        try {
            final InetAddress address = InetAddress.getByName(clientIp);
            return rules.stream().anyMatch(rule -> rule.matches(new InetSocketAddress(address, 0)));
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLiteralAddress(final String value) {
        return value.matches("[0-9a-fA-F.:]+");
    }

    private static IpSubnetFilterRule parseCidr(final String cidr) {
        final String[] parts = cidr.trim().split("/", 2);
        if (parts[0].isBlank()) {
            throw new IllegalArgumentException("Invalid ADMIN_IP_ALLOWLIST entry: '" + cidr + "'");
        }
        final InetAddress address;
        try {
            address = InetAddress.getByName(parts[0].trim());
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("Invalid ADMIN_IP_ALLOWLIST address: '" + cidr + "'", e);
        }
        final int maxPrefix = address.getAddress().length * 8;
        final int prefix = parts.length == 2 ? parsePrefix(parts[1], maxPrefix, cidr) : maxPrefix;
        return new IpSubnetFilterRule(address, prefix, IpFilterRuleType.ACCEPT);
    }

    private static int parsePrefix(final String rawPrefix, final int maxPrefix, final String cidr) {
        final int prefix;
        try {
            prefix = Integer.parseInt(rawPrefix.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ADMIN_IP_ALLOWLIST prefix: '" + cidr + "'", e);
        }
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException("ADMIN_IP_ALLOWLIST prefix out of range: '" + cidr + "'");
        }
        return prefix;
    }
}
