package com.shop.common.spring.tracing;

import com.shop.common.core.constants.MdcKey;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import org.slf4j.MDC;

/**
 * D3 — populates {@code traceId}/{@code spanId} in SLF4J MDC for the lifetime
 * of every trace scope, WITHOUT requiring an exporter (or any events at all).
 *
 * <p>Boot 4 builds {@code OtelCurrentTraceContext} without an event publisher,
 * so its own {@code Slf4JEventListener} never fires; decorating the scopes
 * directly is deterministic and restores the PARENT context on close, so
 * nested outbound spans never leave the request logs without a traceId.</p>
 */
public class MdcOtelCurrentTraceContext extends OtelCurrentTraceContext {

    @Override
    public Scope newScope(TraceContext traceContext) {
        return decorate(traceContext, super.newScope(traceContext));
    }

    @Override
    public Scope maybeScope(TraceContext traceContext) {
        return decorate(traceContext, super.maybeScope(traceContext));
    }

    private Scope decorate(TraceContext traceContext, Scope delegate) {
        apply(traceContext);
        return () -> {
            delegate.close();
            apply(context());
        };
    }

    private void apply(TraceContext traceContext) {
        if (traceContext == null) {
            MDC.remove(MdcKey.TRACE_ID);
            MDC.remove(MdcKey.SPAN_ID);
            return;
        }
        MDC.put(MdcKey.TRACE_ID, traceContext.traceId());
        MDC.put(MdcKey.SPAN_ID, traceContext.spanId());
    }
}
