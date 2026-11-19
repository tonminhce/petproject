package com.shop.common.kafka.consumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class KafkaWireMapper {

    private static final ObjectMapper WIRE_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private KafkaWireMapper() {
    }

    static ObjectMapper mapper() {
        return WIRE_MAPPER;
    }
}
