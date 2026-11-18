package com.shop.common.kafka.producer;

import java.util.List;
import java.util.Map;

public final class OutboxBatchOrdering {

    private OutboxBatchOrdering() {
    }

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
                blockedAggregate.put(key, Boolean.TRUE);
            }
            result[i] = ok && !blockedAggregate.containsKey(key);
        }
        return result;
    }
}
