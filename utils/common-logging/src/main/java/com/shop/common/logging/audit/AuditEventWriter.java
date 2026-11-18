package com.shop.common.logging.audit;

public interface AuditEventWriter extends AutoCloseable {

    void write(AuditEvent event);

    long discardedEvents();

    @Override
    void close();
}
