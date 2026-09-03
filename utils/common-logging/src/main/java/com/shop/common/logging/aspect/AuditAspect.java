package com.shop.common.logging.aspect;

import com.shop.common.core.constants.MdcKey;
import com.shop.common.logging.audit.AuditActorResolver;
import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.audit.AuditResourceResolver;
import com.shop.common.logging.audit.Audited;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * Emits ONE audit event per invocation of an {@link Audited} endpoint.
 *
 * <p>Ordering vs security: the servlet security filter chain runs entirely
 * before DispatcherServlet, so the {@code SecurityContext} is always fully
 * populated by the time any controller advice executes — aspect order cannot
 * affect actor resolution. Among aspects this one runs innermost (lowest
 * precedence) so the outcome and MDC snapshot reflect the state closest to
 * the endpoint.</p>
 *
 * <p>The aspect itself never fails the request: any throwable from the writer
 * is swallowed (the writer also swallows internally), and the joinpoint's
 * exception is always rethrown untouched. Outcome is {@code fail} exactly
 * when the joinpoint threw.</p>
 */
@Aspect
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditEventWriter writer;

    public AuditAspect(AuditEventWriter writer) {
        this.writer = writer;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        try {
            Object result = pjp.proceed();
            emit(audited, AuditEvent.OUTCOME_SUCCESS);
            return result;
        } catch (Throwable t) {
            emit(audited, AuditEvent.OUTCOME_FAIL);
            throw t;
        }
    }

    private void emit(Audited audited, String outcome) {
        try {
            AuditActorResolver.Actor actor = AuditActorResolver.resolve();
            writer.write(new AuditEvent(
                    Instant.now(),
                    actor.id(),
                    actor.type(),
                    audited.action(),
                    audited.resourceType(),
                    AuditResourceResolver.resolve(),
                    outcome,
                    MDC.get(MdcKey.CORRELATION_ID),
                    MDC.get(MdcKey.TRACE_ID)));
        } catch (Exception e) {
            log.warn("audit: could not emit audit event — request unaffected", e);
        }
    }
}
