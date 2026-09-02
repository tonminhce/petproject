package com.shop.common.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the shared Kafka producer / consumer beans.
 *
 * <pre>{@code
 * shop:
 *   kafka:
 *     enabled: true
 *     bootstrap-servers: localhost:9092
 *     topics:
 *       order-events: order-events
 *       payment-events: payment-events
 *     retry:
 *       backoff-ms: 6000
 *       max-attempts: 4
 *     producer:
 *       acks: all
 *       retries: 3
 *     consumer:
 *       group-id: shop-service
 *       auto-offset-reset: earliest
 * }</pre>
 */
@ConfigurationProperties(prefix = "shop.kafka")
public class KafkaProperties {

    /** Toggle the shared Kafka helpers. Default: {@code true}. */
    private boolean enabled = true;

    /** Comma-separated list of {@code host:port} pairs. */
    private String bootstrapServers = "localhost:9092";

    /** Stable topic names used across the platform. */
    private Topics topics = new Topics();

    /** Retry/DLT defaults applied by the shared consumer factory. */
    private Retry retry = new Retry();

    /** Producer-side defaults. */
    private ProducerConfig producer = new ProducerConfig();

    /** Consumer-side defaults. */
    private ConsumerConfig consumer = new ConsumerConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public ProducerConfig getProducer() {
        return producer;
    }

    public void setProducer(ProducerConfig producer) {
        this.producer = producer;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    /** Build the standard producer config map consumed by {@code DefaultKafkaProducerFactory}. */
    public Map<String, Object> buildProducerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("acks", producer.getAcks());
        props.put("retries", producer.getRetries());
        props.put("properties.max.in.flight.requests.per.connection", 1);
        props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        // H41 — pin the transport-layer defaults so every fleet producer gets
        // batching, a small linger window, lz4 compression, and idempotent
        // exactly-once-per-attempt delivery without per-service config. The
        // wire contract (R1, single-encoded JSON-string via KafkaMessagePublisher)
        // is unaffected — these knobs only govern batching + broker-side dedup.
        props.put("enable.idempotence", producer.isEnableIdempotence());
        props.put("compression.type", producer.getCompressionType());
        props.put("batch.size", producer.getBatchSizeBytes());
        props.put("linger.ms", producer.getLingerMs());
        return props;
    }

    /** Build the standard consumer config map consumed by {@code DefaultKafkaConsumerFactory}. */
    public Map<String, Object> buildConsumerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", consumer.getGroupId());
        props.put("auto.offset.reset", consumer.getAutoOffsetReset());
        props.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        return props;
    }

    /** Canonical topic names. */
    public static class Topics {
        private String orderEvents = "order-events";
        private String paymentEvents = "payment-events";
        private String notificationEvents = "notification-events";
        private String inventoryEvents = "inventory-events";

        public String getOrderEvents() {
            return orderEvents;
        }

        public void setOrderEvents(String orderEvents) {
            this.orderEvents = orderEvents;
        }

        public String getPaymentEvents() {
            return paymentEvents;
        }

        public void setPaymentEvents(String paymentEvents) {
            this.paymentEvents = paymentEvents;
        }

        public String getNotificationEvents() {
            return notificationEvents;
        }

        public void setNotificationEvents(String notificationEvents) {
            this.notificationEvents = notificationEvents;
        }

        public String getInventoryEvents() {
            return inventoryEvents;
        }

        public void setInventoryEvents(String inventoryEvents) {
            this.inventoryEvents = inventoryEvents;
        }
    }

    /**
     * Retry/DLT defaults — INTENTIONALLY UNUSED (C20 review finding).
     *
     * <p>The fleet's listener factory uses a raw-String value deserializer
     * ({@link org.apache.kafka.common.serialization.StringDeserializer}, see
     * {@code BaseKafkaListenerConfig}). That deserializer never throws on
     * poison bytes, and {@code BaseKafkaConsumer}-derived listeners follow the
     * "ack-always poison posture" — exceptions inside the handler are caught,
     * logged, and the offset still advances. Combined, neither deserialize
     * failures nor handler failures ever reach a {@code CommonErrorHandler}
     * that could trigger retry / DLT routing.</p>
     *
     * <p>This block is kept for two reasons:</p>
     * <ol>
     *   <li>Future listeners that drop the ack-always posture (e.g. async
     *       processing with a true DLT) can wire these values without a
     *       config change.</li>
     *   <li>Documented fleet precedent so reviewers don't re-flag this as
     *       dead config drift.</li>
     * </ol>
     */
    public static class Retry {
        /** Delay between retry attempts, in milliseconds. */
        private long backoffMs = 6_000L;

        /** Total attempts before routing the record to the DLT. */
        private int maxAttempts = 4;

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /** Producer-side defaults. */
    public static class ProducerConfig {
        private String acks = "all";
        private int retries = 3;

        /**
         * H41 — exactly-once-per-attempt delivery on producer failover.
         * Combined with {@code acks=all} and {@code max.in.flight.requests.
         * per.connection=1} (pinned at the build site), this is the canonical
         * fleet recipe.
         */
        private boolean enableIdempotence = true;

        /**
         * H41 — lz4 is the cheapest CPU cost per byte saved; snappy is faster
         * but compresses less. zstd is also viable but is a separate native
         * dep on the broker side; stick to lz4.
         */
        private String compressionType = "lz4";

        /**
         * H41 — batch size in bytes. 32 KiB amortises one network round-trip
         * across ~20 small JSON envelopes while staying well under the
         * default request size cap.
         */
        private int batchSizeBytes = 32 * 1024;

        /**
         * H41 — linger window in milliseconds. A small (5 ms) wait lets the
         * producer coalesce synchronous outbox sends without adding latency
         * a human caller would notice.
         */
        private int lingerMs = 5;

        public String getAcks() {
            return acks;
        }

        public void setAcks(String acks) {
            this.acks = acks;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public boolean isEnableIdempotence() {
            return enableIdempotence;
        }

        public void setEnableIdempotence(boolean enableIdempotence) {
            this.enableIdempotence = enableIdempotence;
        }

        public String getCompressionType() {
            return compressionType;
        }

        public void setCompressionType(String compressionType) {
            this.compressionType = compressionType;
        }

        public int getBatchSizeBytes() {
            return batchSizeBytes;
        }

        public void setBatchSizeBytes(int batchSizeBytes) {
            this.batchSizeBytes = batchSizeBytes;
        }

        public int getLingerMs() {
            return lingerMs;
        }

        public void setLingerMs(int lingerMs) {
            this.lingerMs = lingerMs;
        }
    }

    /** Consumer-side defaults. */
    public static class ConsumerConfig {
        private String groupId = "shop-service";
        private String autoOffsetReset = "earliest";

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getAutoOffsetReset() {
            return autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }
    }
}
