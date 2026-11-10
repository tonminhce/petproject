package com.shop.common.kafka.producer;

import java.util.List;
import java.util.Map;

/**
 * Decides which records of a published batch may be marked as delivered.
 *
 * <p>A batch is published in one fan-out, so a record that failed to reach
 * the broker does not stop the records behind it from being published. That
 * is fine across aggregates, but NOT within one aggregate: the events of a
 * single aggregate share a partition key, and a consumer that sees a later
 * event before an earlier one ends up with the wrong final state (a refund
 * seen before the capture it compensates) and never converges, because no
 * further event arrives for that aggregate.</p>
 *
 * <p>So the rule is: within one aggregate, a record that failed blocks every
 * later record of the SAME aggregate. Those blocked records go back to the
 * retry set and are published again after the failed one succeeds, which
 * restores the order. Records of other aggregates are unaffected.</p>
 *
 * <p>This is at-least-once: a blocked record may be published twice. The
 * consumer must stay idempotent, which the outbox contract already
 * requires.</p>
 */
public final class OutboxBatchOrdering {

    private OutboxBatchOrdering() {
    }

    /**
     * @param keys      the partition key of every record of the batch, in publish order
     * @param delivered the per-record publish outcome, in publish order; a shorter
     *                  list means the remaining records never completed
     * @return one flag per record: {@code true} when the record may be marked as
     *         published. A record that failed, and every later record of the same
     *         aggregate, is {@code false} and must be retried.
     */
    public static boolean[] markableAsSent(List<String> keys, List<Boolean> delivered) {
        if (keys == null || keys.isEmpty()) {
            return new boolean[0];
        }
        boolean[] result = new boolean[keys.size()];
        Map<String, Boolean> blockedAggregate = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            boolean ok = delivered != null && i < delivered.size()
                    && Boolean.TRUE.equals(delivered.get(i));
            if (!ok) {
                // This aggregate is now blocked: its later records must not be
                // marked as sent even if they themselves reached the broker.
                blockedAggregate.put(key, Boolean.TRUE);
            }
            result[i] = ok && !blockedAggregate.containsKey(key);
        }
        return result;
    }
}
