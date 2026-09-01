package com.shop.common.logging.aspect;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.audit.Audited;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wires the {@link AuditAspect} through a real AspectJ proxy and asserts the
 * D6 binding end-to-end at the unit level: actor from the security context,
 * resourceId from path variables, outcome from the joinpoint result, ids from
 * MDC — and, critically, PII (email/name/username/title) never appearing in
 * the serialized event.
 */
class AuditAspectTest {

    private final RecordingWriter writer = new RecordingWriter();
    private final Target target = new Target();
    private final Target proxy = createProxy();

    @BeforeEach
    void setUpContext() {
        MDC.put(MdcKey.CORRELATION_ID, "corr-42");
        MDC.put(MdcKey.TRACE_ID, "trace-42");
    }

    @AfterEach
    void resetContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    @Test
    void successfulInvocationEmitsSuccessEventWithResolvedFields() {
        login(Map.of("sub", "user-uuid-1", "email", "admin@shop.com"));
        withPathVariables(Map.of("id", "uuid-resource-1"));

        String result = proxy.update("ignored-argument");

        assertThat(result).isEqualTo("ok");
        assertThat(writer.events).hasSize(1);
        AuditEvent event = writer.events.getFirst();
        assertThat(event.actorId()).isEqualTo("user-uuid-1");
        assertThat(event.actorType()).isEqualTo(AuditEvent.ACTOR_TYPE_USER);
        assertThat(event.action()).isEqualTo("update");
        assertThat(event.resourceType()).isEqualTo("product");
        assertThat(event.resourceId()).isEqualTo("uuid-resource-1");
        assertThat(event.outcome()).isEqualTo(AuditEvent.OUTCOME_SUCCESS);
        assertThat(event.correlationId()).isEqualTo("corr-42");
        assertThat(event.traceId()).isEqualTo("trace-42");
    }

    @Test
    void throwingInvocationEmitsFailEventAndRethrows() {
        login(Map.of("clientId", "order-service"));

        assertThatThrownBy(() -> proxy.boom())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(writer.events).hasSize(1);
        AuditEvent event = writer.events.getFirst();
        assertThat(event.actorId()).isEqualTo("order-service");
        assertThat(event.actorType()).isEqualTo(AuditEvent.ACTOR_TYPE_SERVICE);
        assertThat(event.outcome()).isEqualTo(AuditEvent.OUTCOME_FAIL);
    }

    @Test
    void serializedEventNeverContainsPiiEvenWhenAuthenticationHoldsIt() {
        login(Map.of(
                "sub", "user-uuid-2",
                "email", "jane.doe@shop.com",
                "name", "Jane Doe",
                "preferred_username", "jdoe"));
        withPathVariables(Map.of("id", "uuid-resource-2", "title", "Gaming Laptop"));

        proxy.update("Jane Doe");

        assertThat(writer.events).hasSize(1);
        String json = writer.events.getFirst().toJson();
        assertThat(json)
                .doesNotContain("jane.doe@shop.com")
                .doesNotContain("Jane Doe")
                .doesNotContain("jdoe")
                .doesNotContain("Gaming Laptop")
                .contains("\"id\":\"user-uuid-2\"")
                .contains("\"resourceId\":\"uuid-resource-2\"");
    }

    @Test
    void aspectNeverThrowsWhenWriterFails() {
        login(Map.of("sub", "user-uuid-3"));
        RuntimeException boom = new RuntimeException("sink exploded");

        AuditAspect failing = new AuditAspect(new AuditEventWriter() {
            @Override
            public void write(AuditEvent event) {
                throw boom;
            }

            @Override
            public long discardedEvents() {
                return 0;
            }

            @Override
            public void close() {
            }
        });
        Target failingProxy = createProxy(failing);

        assertThat(failingProxy.update("x")).isEqualTo("ok");
        assertThatThrownBy(() -> failingProxy.boom())
                .isInstanceOf(IllegalStateException.class);
    }

    private Target createProxy() {
        return createProxy(new AuditAspect(writer));
    }

    private Target createProxy(AuditAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static void login(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claims(map -> map.putAll(claims))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(jwt, "n/a", List.of()));
    }

    private static void withPathVariables(Map<String, String> pathVariables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, new HashMap<>(pathVariables));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** Public target so the AspectJ proxy can subclass it. */
    public static class Target {

        @Audited(action = "update", resourceType = "product")
        public String update(String ignored) {
            return "ok";
        }

        @Audited(action = "delete", resourceType = "product")
        public void boom() {
            throw new IllegalStateException("boom");
        }
    }

    private static final class RecordingWriter implements AuditEventWriter {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void write(AuditEvent event) {
            events.add(event);
        }

        @Override
        public long discardedEvents() {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
