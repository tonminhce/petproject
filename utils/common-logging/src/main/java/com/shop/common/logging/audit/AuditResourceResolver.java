package com.shop.common.logging.audit;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Resolves the audit resourceId from the current request's URI template
 * variables (populated by Spring MVC's {@code RequestMappingHandlerMapping}).
 *
 * <p>Binding rules (spec D6): only a path variable named exactly {@code id} or
 * ending in {@code Id} qualifies — entity titles, emails or slugs must never
 * become the resourceId. When several {@code *Id} variables exist the
 * lexicographically first wins; when none qualifies the resourceId is
 * {@code null}. Outside a request (async handoff, tests) it is also
 * {@code null}.</p>
 */
public final class AuditResourceResolver {

    static final String EXACT_ID = "id";

    private AuditResourceResolver() {
    }

    public static String resolve() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        Object raw = attributes.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(raw instanceof Map<?, ?> pathVariables)) {
            return null;
        }
        Object exact = pathVariables.get(EXACT_ID);
        if (exact instanceof String id && !id.isBlank()) {
            return id;
        }
        return pathVariables.entrySet().stream()
                .filter(e -> e.getKey() instanceof String key && key.endsWith("Id"))
                .filter(e -> e.getValue() instanceof String value && !value.isBlank())
                .map(e -> Map.entry((String) e.getKey(), (String) e.getValue()))
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }
}
