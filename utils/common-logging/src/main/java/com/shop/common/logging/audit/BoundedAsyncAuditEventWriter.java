package com.shop.common.logging.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * <p>Binding rules (spec D6 + review nit N1, sink recovery per review I-1):</p>
 * <ul>
 *   <li>Pool is hard-bounded: core 2, max 4, queue 1000 — NOT configurable.</li>
 *   <li>Queue overflow DISCARDS the event, increments a counter and logs WARN
 *       (first discard, then every 100th to avoid log flooding) — the caller
 *       never blocks and never sees an exception.</li>
 *   <li>Sink failures (I/O errors) are counted via {@link #failedEvents()},
 *       logged WARN at most once per minute, and swallowed — the file sink
 *       REOPENS on the next event, so a transient failure (disk full, volume
 *       blip) never permanently disables file audit.</li>
 *   <li>Rejections after shutdown are counted as failures, not overflow.</li>
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
    /** Sink-failure WARNs are throttled to one per interval — no per-event spam. */
    static final long SINK_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final ThreadPoolExecutor executor;
    private final Consumer<String> sink;
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong lastSinkWarnNanos = new AtomicLong();

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
                (r, e) -> {
                    if (e.isShutdown()) {
                        // pool already closed — a lost event, not queue overflow
                        recordSinkFailure("audit: writer rejected event (shutting down) — request unaffected", null);
                    } else {
                        onRejection();
                    }
                });
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
                    recordSinkFailure("audit: sink write failed; event dropped — request unaffected", e);
                }
            });
        } catch (RuntimeException e) {
            // Overflow rejections go through the discard handler; this is any
            // other rejection (pool shut down) — a failure, not an overflow.
            recordSinkFailure("audit: writer rejected event (shutting down) — request unaffected", e);
        }
    }

    private void onRejection() {
        long total = discarded.incrementAndGet();
        if (total == 1 || total % WARN_EVERY == 0) {
            log.warn("audit: queue overflow — discarded {} audit events total", total);
        }
    }

    /** Counts the lost event and WARNs at most once per throttle interval. */
    private void recordSinkFailure(String message, Exception cause) {
        long total = failed.incrementAndGet();
        long now = System.nanoTime();
        long last = lastSinkWarnNanos.get();
        if ((last == 0 || now - last >= SINK_WARN_INTERVAL_NANOS)
                && lastSinkWarnNanos.compareAndSet(last, now)) {
            log.warn("{} ({} failed audit events total)", message, total, cause);
        }
    }

    @Override
    public long discardedEvents() {
        return discarded.get();
    }

    /** Events lost to sink failures — distinct from queue-overflow discards. */
    public long failedEvents() {
        return failed.get();
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

    /**
     * Appending, lazily-opened, line-per-event file sink. Thread-safe: all
     * writes go through the executor. On I/O failure the writer is closed so
     * the NEXT event reopens the file — transient failures (disk full, volume
     * blip) recover without a restart; the failure itself propagates to
     * {@link BoundedAsyncAuditEventWriter} for counting and throttled WARN.
     */
    private static final class FileSink implements Consumer<String> {

        private final Path file;
        private BufferedWriter writer;

        private FileSink(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void accept(String line) {
            try {
                if (writer == null) {
                    open();
                }
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                closeWriterQuietly();
                throw new UncheckedIOException("audit: cannot write to " + file, e);
            }
        }

        private void open() throws IOException {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private void closeWriterQuietly() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // best effort — the reopen on the next event is what matters
                }
            }
            writer = null;
        }
    }
}
