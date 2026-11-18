package com.shop.gateway.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class LiteralIpAddress {

    private static final int IPV4_GROUP_COUNT = 4;
    private static final int IPV6_GROUP_COUNT = 8;
    private static final int HEX_GROUP_MAX_LENGTH = 4;

    private LiteralIpAddress() {
    }

    public static Optional<String> canonicalize(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.indexOf(':') >= 0) {
            return canonicalIpv6(trimmed);
        }
        return canonicalIpv4(trimmed);
    }

    public static boolean isLiteral(final String value) {
        return canonicalize(value).isPresent();
    }

    private static Optional<String> canonicalIpv4(final String value) {
        final String[] groups = value.split("\\.", -1);
        if (groups.length != IPV4_GROUP_COUNT) {
            return Optional.empty();
        }
        final StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < groups.length; index++) {
            final String group = groups[index];
            if (!isDecimalOctet(group)) {
                return Optional.empty();
            }
            if (index > 0) {
                canonical.append('.');
            }
            canonical.append(Integer.parseInt(group));
        }
        return Optional.of(canonical.toString());
    }

    private static boolean isDecimalOctet(final String group) {
        if (group.isEmpty() || group.length() > 3) {
            return false;
        }
        for (int index = 0; index < group.length(); index++) {
            if (group.charAt(index) < '0' || group.charAt(index) > '9') {
                return false;
            }
        }
        if (group.length() > 1 && group.charAt(0) == '0') {
            return false;
        }
        return Integer.parseInt(group) <= 255;
    }

    private static Optional<String> canonicalIpv6(final String value) {
        final int compressionIndex = value.indexOf("::");
        if (compressionIndex >= 0 && value.indexOf("::", compressionIndex + 1) >= 0) {
            return Optional.empty();
        }
        final String head = compressionIndex >= 0 ? value.substring(0, compressionIndex) : value;
        final String tail = compressionIndex >= 0 ? value.substring(compressionIndex + 2) : "";
        final Optional<List<String>> headGroups = splitGroups(head);
        final Optional<List<String>> tailGroups = splitGroups(tail);
        if (headGroups.isEmpty() || tailGroups.isEmpty()) {
            return Optional.empty();
        }
        final Optional<List<Integer>> headValues = toValues(headGroups.get(), compressionIndex < 0);
        final Optional<List<Integer>> tailValues = toValues(tailGroups.get(), true);
        if (headValues.isEmpty() || tailValues.isEmpty()) {
            return Optional.empty();
        }
        final int explicitGroups = headValues.get().size() + tailValues.get().size();
        final int zeroGroups;
        if (compressionIndex >= 0) {
            zeroGroups = IPV6_GROUP_COUNT - explicitGroups;
            if (zeroGroups < 1) {
                return Optional.empty();
            }
        } else {
            if (explicitGroups != IPV6_GROUP_COUNT) {
                return Optional.empty();
            }
            zeroGroups = 0;
        }
        final StringBuilder canonical = new StringBuilder();
        appendGroups(canonical, headValues.get());
        for (int index = 0; index < zeroGroups; index++) {
            canonical.append(canonical.length() == 0 ? "0" : ":0");
        }
        appendGroups(canonical, tailValues.get());
        return Optional.of(canonical.toString());
    }

    private static Optional<List<String>> splitGroups(final String part) {
        if (part.isEmpty()) {
            return Optional.of(List.of());
        }
        final String[] raw = part.split(":", -1);
        final List<String> groups = new ArrayList<>(raw.length);
        for (final String group : raw) {
            if (group.isEmpty()) {
                return Optional.empty();
            }
            groups.add(group);
        }
        return Optional.of(groups);
    }

    private static Optional<List<Integer>> toValues(final List<String> groups, final boolean allowEmbeddedIpv4) {
        final List<Integer> values = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            final String group = groups.get(index);
            if (group.indexOf('.') >= 0) {
                if (!allowEmbeddedIpv4 || index != groups.size() - 1) {
                    return Optional.empty();
                }
                final Optional<String> embedded = canonicalIpv4(group);
                if (embedded.isEmpty()) {
                    return Optional.empty();
                }
                final String[] octets = embedded.get().split("\\.");
                values.add((Integer.parseInt(octets[0]) << 8) | Integer.parseInt(octets[1]));
                values.add((Integer.parseInt(octets[2]) << 8) | Integer.parseInt(octets[3]));
                continue;
            }
            if (!isHexGroup(group)) {
                return Optional.empty();
            }
            values.add(Integer.parseInt(group, 16));
        }
        return Optional.of(values);
    }

    private static boolean isHexGroup(final String group) {
        if (group.isEmpty() || group.length() > HEX_GROUP_MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < group.length(); index++) {
            final char character = group.charAt(index);
            final boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void appendGroups(final StringBuilder canonical, final List<Integer> values) {
        for (final int value : values) {
            if (canonical.length() > 0) {
                canonical.append(':');
            }
            canonical.append(Integer.toHexString(value));
        }
    }
}
