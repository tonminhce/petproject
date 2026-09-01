package com.shop.common.logging.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Default {@link AuditEventWriter}: formats each event to a JSON line on the
 * caller thread (cheap, and the event's context is request-bound), then hands
 * the line to a bounded executor that performs the I/O.
 *
 * <p>Binding rules (spec D6 + review nit N1):</p>
 * <ul>
 *   <li>Pool is hard-bounded: core 2, max 4, queue 1000 — NOT configurable.</li>
 *   <li>Queue overflow DISCARDS the event, increments a counter and logs WARN
 *       (first discard, then every 100th to avoid log flooding) — the caller
 *       never blocks and never sees an exception.</li>
 *   <li>Sink failures (I/O errors) are logged and swallowed.</li>
 *   <li>Sink: append to {@code AUDIT_LOG_PATH} when the env var is set, else
 *       one INFO line per event on the {@code AUDIT} logger (stdout).</li>
 * </ul>
 */
public final class BoundedAsyncAuditEventWriter implements AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(BoundedAsyncAuditEventWriter.class);
    /** The dedicated stdout sink — services ship this via their log pipeline. */
    private static final Logger auditStdout = LoggerFactory.getLogger("AUDIT");

    static final String ENV_AUDIT_LOG_PATH = "AUDIT_LOG_PATH";

    // N1: intentionally hard-coded — audit must stay bounded even if someone
    // misconfigures a property file.
    static final int CORE_POOL_SIZE = 2;
    static final int MAX_POOL_SIZE = 4;
    static final int QUEUE_CAPACITY = 1000;

    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final int WARN_EVERY = 100;

    private final ThreadPoolExecutor executor;
    private final Consumer<String> sink;
    private final AtomicLong discarded = new AtomicLong();

    /** Test seam: sink receives fully-formatted JSON lines. */
    BoundedAsyncAuditEventWriter(Consumer<String> sink) {
        this.sink = sink;
        AtomicInteger threadSeq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "audit-writer-" + threadSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (r, e) -> onRejection());
    }

    /** File sink when {@code auditLogPath} is set, stdout {@code AUDIT} logger otherwise. */
    public static BoundedAsyncAuditEventWriter create(String auditLogPath) {
        if (auditLogPath == null || auditLogPath.isBlank()) {
            return new BoundedAsyncAuditEventWriter(auditStdout::info);
        }
        return new BoundedAsyncAuditEventWriter(new FileSink(Path.of(auditLogPath)));
    }

    /** Production factory: reads {@code AUDIT_LOG_PATH} from the environment. */
    public static BoundedAsyncAuditEventWriter create() {
        return create(System.getenv(ENV_AUDIT_LOG_PATH));
    }

    @Override
    public void write(AuditEvent event) {
        String line;
        try {
            line = event.toJson();
        } catch (RuntimeException e) {
            log.warn("audit: failed to serialize event; skipping — request unaffected", e);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    sink.accept(line);
                } catch (Exception e) {
                    log.warn("audit: sink write failed; event dropped — request unaffected", e);
                }
            });
        } catch (RuntimeException e) {
            // Rejections are handled by the discard handler; anything else
            // (shutdown pool) must still never reach the request thread.
            onRejection();
        }
    }

    private void onRejection() {
        long total = discarded.incrementAndGet();
        if (total == 1 || total % WARN_EVERY == 0) {
            log.warn("audit: queue overflow — discarded {} audit events total", total);
        }
    }

    @Override
    public long discardedEvents() {
        return discarded.get();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    ThreadPoolExecutor executorForTesting() {
        return executor;
    }

    /** Appending, lazily-opened, line-per-event file sink. Thread-safe: all writes go through the executor. */
    private static final class FileSink implements Consumer<String> {

        private final Path file;
        private BufferedWriter writer;
        private boolean failed;

        private FileSink(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void accept(String line) {
            if (failed) {
                return; // first open failure is permanent for this instance; log-and-continue
            }
            try {
                if (writer == null) {
                    if (file.getParent() != null) {
                        Files.createDirectories(file.getParent());
                    }
                    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                failed = true;
                log.warn("audit: cannot write to {} — audit events will be dropped for this writer instance",
                        file, e);
            }
        }
    }
}
