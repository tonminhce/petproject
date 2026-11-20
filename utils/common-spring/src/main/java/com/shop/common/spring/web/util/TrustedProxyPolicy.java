package com.shop.common.spring.web.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrustedProxyPolicy {

    public static final String UNKNOWN_IP = "unknown";

    private static final String[] FORWARDED_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "X-Original-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "X-Real-IP"
    };

    private final List<IpNetwork> trustedNetworks;
    private final int hops;

    private TrustedProxyPolicy(List<IpNetwork> trustedNetworks, int hops) {
        this.trustedNetworks = trustedNetworks;
        this.hops = hops;
    }

    public static TrustedProxyPolicy none() {
        return new TrustedProxyPolicy(List.of(), 0);
    }

    public static TrustedProxyPolicy of(List<String> cidrs, int hops) {
        if (cidrs == null || cidrs.isEmpty() || hops <= 0) {
            return none();
        }
        List<IpNetwork> networks = new ArrayList<>();
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            networks.add(IpNetwork.parse(cidr.trim()));
        }
        return new TrustedProxyPolicy(List.copyOf(networks), hops);
    }

    public boolean trustsForwardedHeaders() {
        return hops > 0 && !trustedNetworks.isEmpty();
    }

    public boolean isTrusted(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        byte[] candidate = IpNetwork.literalBytes(address);
        if (candidate == null) {
            return false;
        }
        for (IpNetwork network : trustedNetworks) {
            if (network.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public String resolveClientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN_IP;
        }
        if (!trustsForwardedHeaders() || !isTrusted(peer)) {
            return IpNetwork.normalize(peer);
        }
        String forwarded = firstForwardedHeader(request);
        if (forwarded == null) {
            return IpNetwork.normalize(peer);
        }
        List<String> chain = splitChain(forwarded);
        if (chain.isEmpty()) {
            return IpNetwork.normalize(peer);
        }
        int index = chain.size() - 1;
        int remainingHops = hops;
        while (index >= 0 && remainingHops > 0 && isTrusted(chain.get(index))) {
            index--;
            remainingHops--;
        }
        if (index < 0) {
            return IpNetwork.normalize(peer);
        }
        return IpNetwork.normalize(chain.get(index));
    }

    private static String firstForwardedHeader(HttpServletRequest request) {
        for (String header : FORWARDED_HEADER_CANDIDATES) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> splitChain(String forwarded) {
        List<String> entries = new ArrayList<>();
        for (String raw : forwarded.split(",")) {
            String entry = raw.trim();
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static final class IpNetwork {

        private final byte[] network;
        private final int prefixLength;

        private IpNetwork(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static IpNetwork parse(String cidr) {
            int slash = cidr.indexOf('/');
            String addressPart = slash < 0 ? cidr : cidr.substring(0, slash);
            byte[] bytes = literalBytes(addressPart);
            if (bytes == null) {
                throw new IllegalArgumentException("Not an IP literal: " + cidr);
            }
            int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(cidr.substring(slash + 1).trim());
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr);
            }
            return new IpNetwork(bytes, prefix);
        }

        boolean contains(byte[] candidate) {
            byte[] effective = candidate;
            if (effective.length != network.length) {
                effective = matchLength(effective);
                if (effective == null) {
                    return false;
                }
            }
            int fullBytes = prefixLength / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (network[index] != effective[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (network[fullBytes] & mask) == (effective[fullBytes] & mask);
        }

        private byte[] matchLength(byte[] candidate) {
            if (network.length == 4 && candidate.length == 16 && isIpv4Mapped(candidate)) {
                byte[] mapped = new byte[4];
                System.arraycopy(candidate, 12, mapped, 0, 4);
                return mapped;
            }
            if (network.length == 16 && candidate.length == 4 && isIpv4Mapped(network)) {
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xFF;
                mapped[11] = (byte) 0xFF;
                System.arraycopy(candidate, 0, mapped, 12, 4);
                return mapped;
            }
            return null;
        }

        static boolean isIpv4Mapped(byte[] address) {
            if (address.length != 16) {
                return false;
            }
            for (int index = 0; index < 10; index++) {
                if (address[index] != 0) {
                    return false;
                }
            }
            return (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF;
        }

        static byte[] literalBytes(String literal) {
            if (literal == null || literal.isBlank()) {
                return null;
            }
            String trimmed = literal.trim();
            if (trimmed.indexOf(':') >= 0) {
                try {
                    return InetAddress.getByName(trimmed).getAddress();
                } catch (UnknownHostException notAnAddress) {
                    return null;
                }
            }
            String[] octets = trimmed.split("\\.");
            if (octets.length != 4) {
                return null;
            }
            byte[] bytes = new byte[4];
            for (int index = 0; index < 4; index++) {
                try {
                    int value = Integer.parseInt(octets[index]);
                    if (value < 0 || value > 255) {
                        return null;
                    }
                    bytes[index] = (byte) value;
                } catch (NumberFormatException notAnOctet) {
                    return null;
                }
            }
            return bytes;
        }

        static String normalize(String address) {
            if ("0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address)) {
                return "127.0.0.1";
            }
            return address;
        }
    }
}