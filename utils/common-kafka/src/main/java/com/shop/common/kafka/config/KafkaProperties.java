package com.shop.common.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "shop.kafka")
public class KafkaProperties {

    private boolean enabled = true;

    private String bootstrapServers = "localhost:9092";

    private Retry retry = new Retry();

    private ProducerConfig producer = new ProducerConfig();

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

    public Map<String, Object> buildProducerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("acks", producer.getAcks());
        props.put("retries", producer.getRetries());
        props.put("max.in.flight.requests.per.connection", 1);
        props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        props.put("enable.idempotence", producer.isEnableIdempotence());
        props.put("compression.type", producer.getCompressionType());
        props.put("batch.size", producer.getBatchSizeBytes());
        props.put("linger.ms", producer.getLingerMs());
        return props;
    }

    public Map<String, Object> buildConsumerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", consumer.getGroupId());
        props.put("auto.offset.reset", consumer.getAutoOffsetReset());
        props.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        return props;
    }


    public static class Retry {
        private long backoffMs = 6_000L;

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

    public static class ProducerConfig {
        private String acks = "all";
        private int retries = 3;

        private boolean enableIdempotence = true;

        private String compressionType = "lz4";

        private int batchSizeBytes = 32 * 1024;

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
