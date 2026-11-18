package com.shop.gateway.filter;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

public final class TrustedProxyPolicy {

    private final List<IpSubnetFilterRule> rules;
    private final int hops;

    public TrustedProxyPolicy(final List<String> cidrs, final int hops) {
        if (hops < 0) {
            throw new IllegalArgumentException("hops must be >= 0");
        }
        this.hops = hops;
        this.rules = cidrs == null
                ? List.of()
                : cidrs.stream()
                        .filter(cidr -> cidr != null && !cidr.isBlank())
                        .map(String::trim)
                        .map(TrustedProxyPolicy::parseCidr)
                        .toList();
    }

    public int hops() {
        return hops;
    }

    public boolean trustsForwardedHeaders() {
        return hops > 0 && !rules.isEmpty();
    }

    public boolean isTrusted(final String address) {
        if (address == null || address.isBlank() || rules.isEmpty()) {
            return false;
        }
        final Optional<String> canonical = LiteralIpAddress.canonicalize(address);
        if (canonical.isEmpty()) {
            return false;
        }
        try {
            final InetAddress inetAddress = InetAddress.getByName(canonical.get());
            final InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, 0);
            return rules.stream().anyMatch(rule -> rule.matches(socketAddress));
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    private static IpSubnetFilterRule parseCidr(final String cidr) {
        final String[] parts = cidr.split("/", 2);
        final String address = parts[0].trim();
        final Optional<String> canonical = LiteralIpAddress.canonicalize(address);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("Invalid GATEWAY_TRUSTED_PROXY_CIDRS address: '" + cidr + "'");
        }
        final InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(canonical.get());
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("Invalid GATEWAY_TRUSTED_PROXY_CIDRS address: '" + cidr + "'", e);
        }
        final int maxPrefix = inetAddress.getAddress().length * 8;
        final int prefix = parts.length == 2 ? parsePrefix(parts[1], maxPrefix, cidr) : maxPrefix;
        return new IpSubnetFilterRule(inetAddress, prefix, IpFilterRuleType.ACCEPT);
    }

    private static int parsePrefix(final String rawPrefix, final int maxPrefix, final String cidr) {
        final int prefix;
        try {
            prefix = Integer.parseInt(rawPrefix.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid GATEWAY_TRUSTED_PROXY_CIDRS prefix: '" + cidr + "'", e);
        }
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException("GATEWAY_TRUSTED_PROXY_CIDRS prefix out of range: '" + cidr + "'");
        }
        return prefix;
    }
}
