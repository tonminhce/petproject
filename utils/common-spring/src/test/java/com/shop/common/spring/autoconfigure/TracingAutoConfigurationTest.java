package com.shop.common.spring.autoconfigure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 + N3 — explicit conditional-bean-creation tests for the tracing stack:
 * the OTLP exporter bean must exist ONLY when OTEL_EXPORTER_OTLP_ENDPOINT is
 * present, while the tracer (and MDC log correlation) always is.
 */
class TracingAutoConfigurationTest {

    private static final String ENDPOINT = "http://127.0.0.1:4318/v1/traces";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            OpenTelemetrySdkAutoConfiguration.class,
            org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration.class,
            TracingAutoConfiguration.class));

    @Test
    void tracerPresentAndNoExporterBeanWithoutOtelEnv() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(Tracer.class);
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
            assertThat(ctx).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    void exporterBeanCreatedWhenOtelExporterOtlpEndpointEnvVarSet() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "systemEnvironment", Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", ENDPOINT)));

        runner.withInitializer(ctx -> ctx.setEnvironment(env)).run(ctx -> {
            assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class);
            assertThat(ctx).getBean(SpanExporter.class).isInstanceOf(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    void exporterBeanCreatedWhenCanonicalPropertySet() {
        runner.withPropertyValues("otel.exporter.otlp.endpoint=" + ENDPOINT).run(ctx ->
            assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class));
    }

    @Test
    void mdcCarriesTraceIdAndSpanIdEvenWithoutExporter() {
        runner.run(ctx -> {
            Tracer tracer = ctx.getBean(Tracer.class);
            Span span = tracer.nextSpan().name("mdc-test").start();
            try (var scope = tracer.withSpan(span)) {
                assertThat(MDC.get("traceId")).isEqualTo(span.context().traceId());
                assertThat(MDC.get("spanId")).isEqualTo(span.context().spanId());
            } finally {
                span.end();
            }
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
        });
    }

    @Test
    void restClientCustomizerAppliesTraceparentInterceptor() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RestClientCustomizer.class);

            AtomicReference<MockClientHttpRequest> sent = new AtomicReference<>();
            ClientHttpRequestFactory capturingFactory = (uri, method) -> {
                MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                request.setResponse(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
                sent.set(request);
                return request;
            };
            RestClient.Builder builder = RestClient.builder();
            ctx.getBean(RestClientCustomizer.class).customize(builder);
            RestClient client = builder.requestFactory(capturingFactory).build();

            Tracer tracer = ctx.getBean(Tracer.class);
            Span span = tracer.nextSpan().name("outbound-test").start();
            try (var scope = tracer.withSpan(span)) {
                io.opentelemetry.api.trace.SpanContext otelContext = io.opentelemetry.api.trace.Span
                    .fromContext(io.opentelemetry.context.Context.current()).getSpanContext();
                client.get().uri(URI.create("http://downstream.test/api")).retrieve().toBodilessEntity();

                assertThat(sent.get()).as("outbound request captured").isNotNull();
                assertThat(sent.get().getHeaders().getFirst("traceparent"))
                    .isEqualTo("00-" + otelContext.getTraceId() + "-" + otelContext.getSpanId() + "-"
                        + otelContext.getTraceFlags().asHex());
            } finally {
                span.end();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainBuilderConfig {
        @Bean
        RestClient.Builder myBuilder() {
            return RestClient.builder();
        }
    }

    /** R1 — an arbitrary RestClient.Builder bean is enriched with no wiring of its own. */
    @Test
    void beanPostProcessorEnrichesArbitraryRestClientBuilderBean() {
        runner.withUserConfiguration(PlainBuilderConfig.class).run(ctx -> {
            assertThat(ctx).hasBean("traceparentRestClientBuilderPostProcessor");

            RestClient.Builder builder = ctx.getBean(RestClient.Builder.class);
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
            builder.requestInterceptors(interceptors::addAll);
            assertThat(interceptors).hasSize(1);
            assertThat(interceptors.get(0))
                .isInstanceOf(com.shop.common.spring.tracing.TraceparentInterceptor.class)
                .isSameAs(ctx.getBean(com.shop.common.spring.tracing.TraceparentInterceptor.class));

            AtomicReference<MockClientHttpRequest> sent = new AtomicReference<>();
            ClientHttpRequestFactory capturingFactory = (uri, method) -> {
                MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                request.setResponse(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
                sent.set(request);
                return request;
            };
            RestClient client = builder.requestFactory(capturingFactory).build();

            Tracer tracer = ctx.getBean(Tracer.class);
            Span span = tracer.nextSpan().name("bpp-roundtrip").start();
            try (var scope = tracer.withSpan(span)) {
                io.opentelemetry.api.trace.SpanContext otelContext = io.opentelemetry.api.trace.Span
                    .fromContext(io.opentelemetry.context.Context.current()).getSpanContext();
                client.get().uri(URI.create("http://downstream.test/api")).retrieve().toBodilessEntity();

                assertThat(sent.get()).as("outbound request captured").isNotNull();
                assertThat(sent.get().getHeaders().getFirst("traceparent"))
                    .isEqualTo("00-" + otelContext.getTraceId() + "-" + otelContext.getSpanId() + "-"
                        + otelContext.getTraceFlags().asHex());
            } finally {
                span.end();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PreLoadedBuilderConfig {
        @Bean
        RestClient.Builder preLoadedBuilder() {
            return RestClient.builder().requestInterceptor(new com.shop.common.spring.tracing.TraceparentInterceptor());
        }
    }

    /** R1 — a builder that already carries the interceptor is NOT double-instrumented. */
    @Test
    void beanPostProcessorIsIdempotentWhenInterceptorPreAdded() {
        runner.withUserConfiguration(PreLoadedBuilderConfig.class).run(ctx -> {
            RestClient.Builder builder = ctx.getBean(RestClient.Builder.class);
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
            builder.requestInterceptors(interceptors::addAll);

            assertThat(interceptors)
                .hasSize(1)
                .allMatch(com.shop.common.spring.tracing.TraceparentInterceptor.class::isInstance);
        });
    }

    /**
     * Fleet boot proof — services that do not carry {@code spring-boot-restclient}
     * on their runtime classpath (test-scope only) must still start the tracing
     * configuration: outer-class introspection must not touch the guarded nested
     * RestClient wiring.
     */
    @Test
    void bootsWithoutSpringBootRestclientOnClasspath() {
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader hiding = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.springframework.boot.restclient")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        runner.withClassLoader(hiding).run(ctx -> {
            assertThat(ctx).hasSingleBean(com.shop.common.spring.tracing.TraceparentInterceptor.class);
            assertThat(ctx).hasBean("mdcOtelCurrentTraceContext");
            assertThat(ctx).doesNotHaveBean("traceparentRestClientCustomizer");
            assertThat(ctx).doesNotHaveBean("traceparentRestClientBuilderPostProcessor");
        });
    }
}
