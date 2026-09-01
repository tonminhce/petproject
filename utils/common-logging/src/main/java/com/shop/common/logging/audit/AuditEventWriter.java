package com.shop.common.logging.audit;

/**
 * Sink for audit events — one event becomes one JSON line, exactly once.
 *
 * <p>Contract (spec D6 + review nit N1): {@link #write} NEVER blocks the
 * request thread beyond O(microseconds) of formatting, NEVER throws, and
 * never propagates audit failures — an audit outage must not fail the
 * business operation.</p>
 */
public interface AuditEventWriter extends AutoCloseable {

    /** Enqueue the event; returns immediately. Discards on overflow (counted). */
    void write(AuditEvent event);

    /** Number of events discarded because the bounded queue was full. */
    long discardedEvents();

    /** Release resources; waits briefly for in-flight lines to be flushed. */
    @Override
    void close();
}
