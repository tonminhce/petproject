package com.shop.common.security.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SecurityPropertiesTest {

    @Test
    @DisplayName("E1: valid rule (GET, '/foo') → constructs; accessors return the same values")
    void validRuleWithMethodConstructs() {
        SecurityProperties.EndpointRule rule =
            new SecurityProperties.EndpointRule(HttpMethod.GET, "/foo");

        assertThat(rule.method()).isEqualTo(HttpMethod.GET);
        assertThat(rule.path()).isEqualTo("/foo");
    }

    @Test
    @DisplayName("E2: valid rule (null, '/foo') → constructs (null means any HTTP method)")
    void validRuleWithAnyMethodConstructs() {
        SecurityProperties.EndpointRule rule =
            new SecurityProperties.EndpointRule(null, "/foo");

        assertThat(rule.method()).isNull();
        assertThat(rule.path()).isEqualTo("/foo");
    }

    @Test
    @DisplayName("E3: null path → IllegalArgumentException with 'must not be blank' message")
    void nullPathRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SecurityProperties.EndpointRule(HttpMethod.POST, null))
            .withMessageContaining("EndpointRule.path must not be blank");
    }

    @Test
    @DisplayName("E4: blank path '' → IllegalArgumentException")
    void emptyPathRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SecurityProperties.EndpointRule(HttpMethod.POST, ""))
            .withMessageContaining("EndpointRule.path must not be blank");
    }

    @Test
    @DisplayName("E5: whitespace-only path '   ' → IllegalArgumentException")
    void whitespacePathRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SecurityProperties.EndpointRule(null, "   "))
            .withMessageContaining("EndpointRule.path must not be blank");
    }
}
