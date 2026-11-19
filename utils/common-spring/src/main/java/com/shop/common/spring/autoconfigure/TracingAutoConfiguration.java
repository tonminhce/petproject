package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.tracing.MdcOtelCurrentTraceContext;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@AutoConfiguration(before = OpenTelemetryTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.api.trace.Span")
public class TracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceparentInterceptor traceparentInterceptor() {
        return new TraceparentInterceptor();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer.class)
    static class RestClientTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "traceparentRestClientCustomizer")
        public RestClientCustomizer traceparentRestClientCustomizer(TraceparentInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }

        @Bean
        @ConditionalOnMissingBean(name = "traceparentRestClientBuilderPostProcessor")
        public static BeanPostProcessor traceparentRestClientBuilderPostProcessor() {
            return new TraceparentRestClientBuilderPostProcessor();
        }

        static final class TraceparentRestClientBuilderPostProcessor implements BeanPostProcessor, BeanFactoryAware {

            private static final Logger LOG =
                LoggerFactory.getLogger(TraceparentRestClientBuilderPostProcessor.class);

            private final AtomicReference<TraceparentInterceptor> interceptor = new AtomicReference<>();

            private BeanFactory beanFactory;

            @Override
            public void setBeanFactory(BeanFactory beanFactory) {
                this.beanFactory = beanFactory;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestClient.Builder builder) {
                    List<ClientHttpRequestInterceptor> existing = new ArrayList<>();
                    builder.requestInterceptors(existing::addAll);
                    if (existing.stream().noneMatch(TraceparentInterceptor.class::isInstance)) {
                        TraceparentInterceptor resolved = interceptor();
                        if (resolved != null) {
                            builder.requestInterceptor(resolved);
                        }
                    }
                }
                return bean;
            }

            private TraceparentInterceptor interceptor() {
                TraceparentInterceptor current = interceptor.get();
                if (current != null) {
                    return current;
                }
                TraceparentInterceptor resolved = beanFactory
                    .getBeanProvider(TraceparentInterceptor.class)
                    .getIfAvailable();
                if (resolved == null) {
                    LOG.debug("No TraceparentInterceptor bean — skipping RestClient.Builder enrichment");
                    return null;
                }
                interceptor.set(resolved);
                return resolved;
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(OtelCurrentTraceContext.class)
    public MdcOtelCurrentTraceContext mdcOtelCurrentTraceContext() {
        return new MdcOtelCurrentTraceContext();
    }

    @Bean
    @ConditionalOnMissingBean(SpanExporter.class)
    @ConditionalOnProperty("otel.exporter.otlp.endpoint")
    public OtlpHttpSpanExporter otlpSpanExporter(
            @Value("${otel.exporter.otlp.endpoint}") String endpoint) {
        return OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
    }
}
