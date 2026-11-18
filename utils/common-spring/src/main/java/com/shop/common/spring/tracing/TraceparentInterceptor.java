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
