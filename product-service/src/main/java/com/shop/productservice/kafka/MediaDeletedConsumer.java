package com.shop.productservice.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.productservice.service.ProductMediaService;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Media lifecycle consumer (media epic spec D4, group product-service): on
 * {@code MediaDeleted} every live product referencing the media is cleared
 * and re-published as ProductUpdated (→ search doc refresh via the existing
 * chain). Other event types — e.g. the audit-only {@code MediaCreated} — are
 * ack-skipped.
 *
 * <p>Wire contract (R1 + H-1): producers publish JSON-as-String via the fleet
 * {@code KafkaMessagePublisher}, so records arrive SINGLE-ENCODED UTF-8 JSON.
 * The raw value is parsed once and, when textual (a LEGACY double-encoded
 * token — a JSON string token wrapping the event JSON, from pre-R1 producers),
 * unwrapped and parsed again; both shapes bind. Malformed bytes (poison
 * records) are contained at parse time — never a listener throw.</p>
 *
 * <p>Ack-always poison posture (ProductRatingConsumer precedent): the
 * listener method must never throw — a handler failure is logged and
 * swallowed so the offset still advances. No DLT (fleet containment rule).</p>
 *
 * <p>H-3 bounded retry: {@code clearReference} is a DB write, and the fleet
 * posture acks every failure — so a TRANSIENT storage failure would otherwise
 * drop a clear until the next media event. The clear is therefore retried
 * in-consumer up to {@value #MAX_ATTEMPTS} attempts with a short backoff,
 * then logged at ERROR and acked (posture preserved — the H-3 reconciliation
 * sweep is the durable backstop). Parse/unknown-eventType failures stay
 * immediate ack-skip.</p>
 *
 * <p>Retry scope is {@link TransientDataAccessException}, NOT the broader
 * {@code DataAccessException}: TransientDataAccessException IS a
 * DataAccessException subclass (QueryTimeout, deadlock/serialization,
 * optimistic-locking failures — conditions where a retry can succeed), so
 * narrowing is strictly the transient subset while the same import family
 * keeps the fleet vocabulary. Non-transient data failures (constraint
 * violations, bad SQL grammar, data conversion) are permanent for this
 * payload — retrying them only burns the backoff before the same ack, so
 * they go straight to the ERROR+ack containment.</p>
 */
@Component
public class MediaDeletedConsumer extends BaseKafkaConsumer<String, String> {

    static final int MAX_ATTEMPTS = 3;
    static final long BACKOFF_MILLIS = 200;

    private final ProductMediaService productMediaService;
    private final ObjectMapper objectMapper;

    public MediaDeletedConsumer(ProductMediaService productMediaService, ObjectMapper objectMapper) {
        this.productMediaService = productMediaService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "media.lifecycle.v1", containerFactory = "mediaListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {
        processMessage(rawValue, headers, this::handleContained);
    }

    private void handleContained(String rawValue) {
        try {
            MediaLifecycleEvent event = decode(rawValue);
            if (event == null || event.mediaId() == null) {
                log.info("Media event without mediaId — ack-skipping: {}", rawValue);
                return;
            }
            switch (event.eventType() == null ? "" : event.eventType()) {
                case "MediaDeleted" -> clearWithBoundedRetry(event.mediaId());
                default -> log.info("Skipping unknown media eventType {} (mediaId={})",
                    event.eventType(), event.mediaId());
            }
        } catch (Exception ex) {
            log.error("Failed to process media lifecycle payload", ex);
        }
    }

    /**
     * H-3: bounded retry for transient storage failures only — a clear lost to
     * a DB blip gets {@value #MAX_ATTEMPTS} total attempts; anything else
     * (or exhaustion) falls through to the ack-always containment, where the
     * sweep job owns eventual repair. TransientDataAccessException is a
     * DataAccessException subclass, so this is the strict transient subset
     * (see class javadoc for the narrowing rationale).
     */
    private void clearWithBoundedRetry(UUID mediaId) {
        for (int attempt = 1; ; attempt++) {
            try {
                productMediaService.clearReference(mediaId);
                return;
            } catch (TransientDataAccessException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Media-deleted clear FAILED for media {} after {} attempts — ack-skipping " +
                        "(reconciliation sweep is the backstop)", mediaId, attempt, ex);
                    return;
                }
                log.warn("Transient failure clearing media {} (attempt {}/{}), retrying in {}ms",
                    mediaId, attempt, MAX_ATTEMPTS, BACKOFF_MILLIS, ex);
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private MediaLifecycleEvent decode(String rawValue) throws Exception {
        JsonNode node = objectMapper.readTree(rawValue);
        if (node.isTextual()) {
            // Legacy double-encoded wire (pre-R1): an old producer
            // JSON-string-encoded the payload — unwrap once before binding.
            node = objectMapper.readTree(node.textValue());
        }
        return objectMapper.treeToValue(node, MediaLifecycleEvent.class);
    }
}
