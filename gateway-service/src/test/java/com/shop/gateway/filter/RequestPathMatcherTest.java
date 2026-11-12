package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathMatcherTest {

    @Test
    void thePrefixItselfMatches() {
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/ratings", "/api/v1/ratings")).isTrue();
    }

    @Test
    void aChildOfThePrefixMatches() {
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/ratings/42", "/api/v1/ratings")).isTrue();
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/ratings/42/reviews", "/api/v1/ratings")).isTrue();
    }

    @Test
    void aPathThatOnlySharesThePrefixTextDoesNotMatch() {
        // the whole point: a sibling route must not inherit the bypass
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/ratings-admin", "/api/v1/ratings")).isFalse();
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/ratingsadmin", "/api/v1/ratings")).isFalse();
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/auth-internal", "/api/v1/auth")).isFalse();
    }

    @Test
    void anUnrelatedPathDoesNotMatch() {
        assertThat(RequestPathMatcher.matchesPrefix("/api/v1/backoffice/products", "/api/v1/products")).isFalse();
        assertThat(RequestPathMatcher.matchesPrefix("/actuator/health", "/api/v1/products")).isFalse();
    }

    @Test
    void anyPrefixMatchesOnASegmentBoundary() {
        var prefixes = List.of("/api/v1/products", "/api/v1/auth");

        assertThat(RequestPathMatcher.matchesAnyPrefix("/api/v1/auth/login", prefixes)).isTrue();
        assertThat(RequestPathMatcher.matchesAnyPrefix("/api/v1/products/1", prefixes)).isTrue();
        assertThat(RequestPathMatcher.matchesAnyPrefix("/api/v1/auth-internal", prefixes)).isFalse();
        assertThat(RequestPathMatcher.matchesAnyPrefix("/api/v1/backoffice/products", prefixes)).isFalse();
    }
}
