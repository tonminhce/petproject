package com.shop.common.core.audit;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The ONE convention for the actor of an audited change (F-049).
 *
 * <p>The fleet used two conventions at the same time: {@code
 * auditorAware.getCurrentAuditor().orElse("system")} in most services and
 * {@code ...orElseThrow()} in the promotion and tax services. The strict
 * variant throws a raw {@link java.util.NoSuchElementException}, which the API
 * layer turns into an opaque 500 - the operator sees a stack trace instead of
 * a domain error. The lenient variant silently records "system" even when the
 * caller WAS identified, so the audit trail loses information without saying
 * so.</p>
 *
 * <p>Both sites now use {@link #require(Supplier)}: the actor must resolve,
 * and when it does not, the caller gets a domain error
 * ({@code AUDIT_ACTOR_UNAVAILABLE}) instead of a raw exception. The shared
 * {@code AuditorAware} of common-spring always resolves - it falls back to
 * "system" when the request has no authenticated principal - so in a normal
 * deployment this changes nothing; it only turns a misconfigured auditor into
 * a clean, explicit failure.</p>
 *
 * <p>Background jobs must state their actor explicitly with
 * {@link #system(String)} - a scheduled task has no user, and saying so is
 * more honest than pretending an actor was resolved.</p>
 */
public final class AuditActor {

    /** The actor recorded when a background job performs the change. */
    public static final String SYSTEM = "system";

    private AuditActor() {
    }

    /**
     * The actor of a request-driven change.
     *
     * @param auditor supplies the current auditor (typically
     *                {@code auditorAware::getCurrentAuditor})
     * @return the resolved actor, never blank
     * @throws BusinessException {@code AUDIT_ACTOR_UNAVAILABLE} when no actor
     *                           can be resolved
     */
    public static String require(Supplier<Optional<String>> auditor) {
        String actor = auditor == null ? null : auditor.get().orElse(null);
        if (actor == null || actor.isBlank()) {
            throw BusinessException.of(ErrorCode.AUDIT_ACTOR_UNAVAILABLE);
        }
        return actor;
    }

    /**
     * The actor of a background job.
     *
     * @param job a short name of the job, recorded as {@code system:<job>} so
     *             the audit trail says WHICH job acted
     */
    public static String system(String job) {
        if (job == null || job.isBlank()) {
            throw new IllegalArgumentException("the job name must not be blank");
        }
        return SYSTEM + ":" + job;
    }
}
