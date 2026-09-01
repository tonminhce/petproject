package com.shop.common.logging.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6 binding: resourceId comes ONLY from a path variable named {@code id} or
 * {@code *Id}; titles, emails or other variables never qualify.
 */
class AuditResourceResolverTest {

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void outsideRequestReturnsNull() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(AuditResourceResolver.resolve()).isNull();
    }

    @Test
    void exactIdPathVariableWins() {
        withPathVariables(Map.of("id", "uuid-1", "productId", "uuid-2"));

        assertThat(AuditResourceResolver.resolve()).isEqualTo("uuid-1");
    }

    @Test
    void suffixedIdPathVariableIsUsedWhenNoExactId() {
        withPathVariables(Map.of("orderId", "uuid-9"));

        assertThat(AuditResourceResolver.resolve()).isEqualTo("uuid-9");
    }

    @Test
    void lexicographicallyFirstSuffixedVariableWinsWhenAmbiguous() {
        withPathVariables(Map.of("childId", "uuid-c", "parentId", "uuid-p"));

        assertThat(AuditResourceResolver.resolve()).isEqualTo("uuid-c");
    }

    @Test
    void nonIdVariablesNeverQualify() {
        withPathVariables(Map.of("title", "Gaming Laptop", "slug", "gaming-laptop"));

        assertThat(AuditResourceResolver.resolve()).isNull();
    }

    @Test
    void blankValuesAreIgnored() {
        withPathVariables(Map.of("id", "  "));

        assertThat(AuditResourceResolver.resolve()).isNull();
    }

    private static void withPathVariables(Map<String, String> pathVariables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, new HashMap<>(pathVariables));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
