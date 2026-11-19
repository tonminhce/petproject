package com.shop.common.spring.tracing;

import com.shop.common.core.constants.MdcKey;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import org.slf4j.MDC;

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
