package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiteralIpAddressTest {

    @Test
    void ipv4LiteralsAreAccepted() {
        assertThat(LiteralIpAddress.canonicalize("203.0.113.7")).contains("203.0.113.7");
        assertThat(LiteralIpAddress.canonicalize("0.0.0.0")).contains("0.0.0.0");
        assertThat(LiteralIpAddress.canonicalize("255.255.255.255")).contains("255.255.255.255");
    }

    @Test
    void hostnamesAreRejected() {
        assertThat(LiteralIpAddress.canonicalize("attacker.example")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("cafe")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("localhost")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("dead.beef")).isEmpty();
    }

    @Test
    void malformedIpv4LiteralsAreRejected() {
        assertThat(LiteralIpAddress.canonicalize("203.0.113")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("203.0.113.7.9")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("203.0.113.256")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("203.0.113.-7")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("203.0.113.007")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("203.0.113.7:41234")).isEmpty();
    }

    @Test
    void ipv6LiteralsAreAcceptedAndExpanded() {
        assertThat(LiteralIpAddress.canonicalize("::1")).contains("0:0:0:0:0:0:0:1");
        assertThat(LiteralIpAddress.canonicalize("2001:db8::1")).contains("2001:db8:0:0:0:0:0:1");
        assertThat(LiteralIpAddress.canonicalize("2001:0db8:0000:0000:0000:0000:0000:0001"))
                .contains("2001:db8:0:0:0:0:0:1");
        assertThat(LiteralIpAddress.canonicalize("::ffff:192.0.2.1")).contains("0:0:0:0:0:ffff:c000:201");
    }

    @Test
    void malformedIpv6LiteralsAreRejected() {
        assertThat(LiteralIpAddress.canonicalize("2001:db8::1::2")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("2001:db8:0:0:0:0:0:0:1")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("2001:db8:zzzz::1")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("12345::1")).isEmpty();
    }

    @Test
    void blankValuesAreRejected() {
        assertThat(LiteralIpAddress.canonicalize(null)).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("")).isEmpty();
        assertThat(LiteralIpAddress.canonicalize("   ")).isEmpty();
    }
}
