package com.shop.common.spring.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * D3 — injects the W3C {@code traceparent} (and {@code tracestate} when the
 * current span context carries one) header from the CURRENT span context on
 * every outbound request. No header is injected when no span is active.
 *
 * <p>Uses only the OTel API, so it can be wired into any fleet
 * {@code RestClient.builder()} regardless of how the client was built.</p>
 */
public class TraceparentInterceptor implements ClientHttpRequestInterceptor {

    private static final TextMapPropagator W3C = io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
    private static final TextMapSetter<HttpHeaders> SETTER = HttpHeaders::set;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Context current = Context.current();
        if (Span.fromContext(current).getSpanContext().isValid()) {
            W3C.inject(current, request.getHeaders(), SETTER);
        }
        return execution.execute(request, body);
    }
}
