package com.shop.common.logging.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose invocations must produce ONE structured audit
 * event (JSON line) — see the production-readiness spec, section D6.
 *
 * <p>Deliberately attribute-free beyond {@code action} and {@code resourceType}:
 * the actor comes from the security context (JWT {@code sub} for users,
 * {@code clientId}/{@code azp} for service tokens — never email/phone/name),
 * the resourceId from a path variable named {@code id} or {@code *Id} (never
 * titles), the outcome from whether the method threw, and
 * correlationId/traceId from the MDC. Nothing to configure per call site.</p>
 *
 * <p>Apply to mutating endpoints only (POST/PUT/PATCH/DELETE).</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** What was done, e.g. {@code "create"} or {@code "update-status"}. */
    String action();

    /** Kind of resource acted on, e.g. {@code "product"}. */
    String resourceType();
}
