package com.shop.common.kafka.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.List;

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
