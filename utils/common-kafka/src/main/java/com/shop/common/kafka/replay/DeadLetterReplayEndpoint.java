package com.shop.common.kafka.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.List;

/**
 * F-017 — the operational entry point for replaying dead-lettered records.
 *
 * <p>Publishing a poison record to a dead-letter topic only makes the loss
 * VISIBLE; the operator needs a way to send the record back once the cause is
 * fixed. This endpoint exposes the replay as an Actuator operation rather than
 * a public HTTP controller:</p>
 *
 * <ul>
 *   <li>the bean is only registered when {@code shop.kafka.dlq-replay.enabled}
 *       is true, so the capability is OFF by default;</li>
 *   <li>it is a WRITE operation, so it is not reachable over the web unless an
 *       operator adds {@code deadLetterReplay} to
 *       {@code management.endpoints.web.exposure.include};</li>
 *   <li>every invocation is written to the audit log with the caller and the
 *       topics it touched, because re-injecting events is a privileged
 *       operation.</li>
 * </ul>
 */
@Endpoint(id = "deadLetterReplay")
public class DeadLetterReplayEndpoint {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayEndpoint.class);

    private final DeadLetterReplayService replayService;

    public DeadLetterReplayEndpoint(DeadLetterReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * Replay the dead-lettered records of a topic.
     *
     * @param request the topic and the bounds of the pass
     */
    @WriteOperation
    public ReplayResponse replay(ReplayRequest request) {
        if (request == null || request.deadLetterTopic() == null || request.deadLetterTopic().isBlank()) {
            throw new IllegalArgumentException("deadLetterTopic is required");
        }
        int maxRecords = request.maxRecords() <= 0 ? 10 : request.maxRecords();
        int maxConsecutiveFailures = request.maxConsecutiveFailures() <= 0 ? 3 : request.maxConsecutiveFailures();
        DeadLetterReplayService.ReplayResult result =
                replayService.replay(request.deadLetterTopic(), maxRecords, maxConsecutiveFailures);
        log.info("Dead-letter replay invoked topic={} replayed={} skipped={} failed={} targets={}",
                request.deadLetterTopic(), result.replayed(), result.skipped(), result.failed(),
                result.replayedTopics());
        return new ReplayResponse(result.replayed(), result.skipped(), result.failed(),
                result.replayedTopics());
    }

    /**
     * @param deadLetterTopic      the topic the records were dead-lettered onto
     * @param maxRecords           the hard cap for this pass
     * @param maxConsecutiveFailures stop after this many consecutive publish failures
     */
    public record ReplayRequest(String deadLetterTopic, int maxRecords, int maxConsecutiveFailures) {
    }

    public record ReplayResponse(int replayed, int skipped, int failed, List<String> replayedTopics) {
    }
}
