package com.shop.common.logging.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * N1 binding: the writer's pool is hard-bounded (core 2 / max 4 / queue 1000),
 * overflow discards + WARNs without blocking or failing the caller, and sink
 * failures are logged-and-continued. The sink is the {@code AUDIT_LOG_PATH}
 * file when the path is present, the stdout {@code AUDIT} logger otherwise.
 */
class BoundedAsyncAuditEventWriterTest {

    private static final AuditEvent EVENT = new AuditEvent(
            Instant.parse("2026-09-01T10:15:30Z"), "user-1", "user", "create", "product",
            "uuid-1", "success", "corr-1", "trace-1");

    @TempDir
    Path tempDir;

    @Test
    void executorIsHardBoundedPerN1() {
        BoundedAsyncAuditEventWriter writer = new BoundedAsyncAuditEventWriter(line -> {
        });
        try {
            assertThat(writer.executorForTesting().getCorePoolSize()).isEqualTo(2);
            assertThat(writer.executorForTesting().getMaximumPoolSize()).isEqualTo(4);
            assertThat(writer.executorForTesting().getQueue().remainingCapacity()).isEqualTo(1000);
        } finally {
            writer.close();
        }
    }

    @Test
    void fileModeWritesOneJsonLinePerEvent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit").resolve("audit.log");

        BoundedAsyncAuditEventWriter writer = BoundedAsyncAuditEventWriter.create(file.toString());
        writer.write(EVENT);
        writer.write(eventWithOutcome(AuditEvent.OUTCOME_FAIL));
        writer.close();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(EVENT.toJson());
        assertThat(lines.get(1)).isEqualTo(eventWithOutcome(AuditEvent.OUTCOME_FAIL).toJson());
    }

    @Test
    void stdoutModeLogsOneLinePerEventOnAuditLogger() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try (BoundedAsyncAuditEventWriter writer = new BoundedAsyncAuditEventWriter(auditLogger::info)) {
            writer.write(EVENT);
        }

        auditLogger.detachAppender(appender);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo(EVENT.toJson());
    }

    @Test
    void queueOverflowDiscardsAndWarnsButNeverBlocksOrFailsTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        BoundedAsyncAuditEventWriter writer = new BoundedAsyncAuditEventWriter(line -> {
            written.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Logger writerLogger = (Logger) LoggerFactory.getLogger(BoundedAsyncAuditEventWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        writerLogger.addAppender(appender);

        try {
            for (int i = 0; i < 1010; i++) {
                assertThatNoException().isThrownBy(() -> writer.write(EVENT));
            }
            assertThat(writer.discardedEvents()).isGreaterThanOrEqualTo(1);
        } finally {
            release.countDown();
            writer.close();
            writerLogger.detachAppender(appender);
        }

        assertThat(written.get()).isGreaterThanOrEqualTo(1000);
        assertThat(appender.list)
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("queue overflow", "1 audit events total");
                });
    }

    @Test
    void sinkFailureIsSwallowedCountedAndWarnedThrottled() throws Exception {
        BoundedAsyncAuditEventWriter writer = new BoundedAsyncAuditEventWriter(line -> {
            throw new RuntimeException("disk on fire");
        });
        Logger writerLogger = (Logger) LoggerFactory.getLogger(BoundedAsyncAuditEventWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        writerLogger.addAppender(appender);
        try {
            assertThatNoException().isThrownBy(() -> {
                writer.write(EVENT);
                writer.write(EVENT);
            });
            awaitUntil(() -> writer.failedEvents() == 2);
        } finally {
            writer.close();
            writerLogger.detachAppender(appender);
        }

        assertThat(writer.failedEvents()).isEqualTo(2);
        assertThat(writer.discardedEvents()).isZero();
        long sinkFailureWarns = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("sink write failed"))
                .count();
        assertThat(sinkFailureWarns).isEqualTo(1); // throttled: NOT one WARN per event
    }

    @Test
    void transientFileFailureRecoversForSubsequentEvents() throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Path file = blocker.resolve("audit.log");
        Files.createFile(blocker); // parent exists as a FILE -> first open must fail

        AuditEvent second = eventWithOutcome(AuditEvent.OUTCOME_FAIL);
        String secondJson = second.toJson();
        BoundedAsyncAuditEventWriter writer = BoundedAsyncAuditEventWriter.create(file.toString());
        try {
            writer.write(EVENT);
            awaitUntil(() -> writer.failedEvents() == 1);

            // transient condition cleared: blocker file becomes a directory
            Files.delete(blocker);
            Files.createDirectory(blocker);

            writer.write(second);
        } finally {
            writer.close();
        }

        // sink was NOT permanently disabled: the second event is recorded
        assertThat(Files.readAllLines(file)).containsExactly(secondJson);
        assertThat(writer.failedEvents()).isEqualTo(1);
    }

    @Test
    void shutdownRejectionCountsAsFailureNotOverflow() {
        BoundedAsyncAuditEventWriter writer = new BoundedAsyncAuditEventWriter(line -> {
        });
        writer.close();

        assertThatNoException().isThrownBy(() -> writer.write(EVENT));

        assertThat(writer.failedEvents()).isEqualTo(1);
        assertThat(writer.discardedEvents()).isZero();
    }

    @Test
    void unwritableFileIsLoggedAndContinued() {
        Path blocker = tempDir.resolve("blocker");
        Path unwritable = blocker.resolve("audit.log");
        BoundedAsyncAuditEventWriter writer = BoundedAsyncAuditEventWriter.create(unwritable.toString());
        try {
            Files.createFile(blocker); // parent exists as a FILE -> open must fail

            assertThatNoException().isThrownBy(() -> {
                writer.write(EVENT);
                writer.write(EVENT);
            });
            awaitUntil(() -> writer.failedEvents() == 2);
        } catch (Exception e) {
            throw new AssertionError("test setup failed", e);
        } finally {
            writer.close();
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(10);
        }
    }

    private static AuditEvent eventWithOutcome(String outcome) {
        return new AuditEvent(EVENT.timestamp(), EVENT.actorId(), EVENT.actorType(), EVENT.action(),
                EVENT.resourceType(), EVENT.resourceId(), outcome, EVENT.correlationId(), EVENT.traceId());
    }
}
