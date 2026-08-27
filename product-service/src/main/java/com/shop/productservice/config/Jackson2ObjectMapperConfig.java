package com.shop.productservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 switched the default {@link ObjectMapper} autoconfig to Jackson 3
 * ({@code tools.jackson.databind.json.JsonMapper}). Several platform beans in this
 * project — the outbox publisher and the common-kafka serializer/deserializer —
 * still consume the classic Jackson 2 {@link ObjectMapper} and Jackson 2 is on
 * the classpath via common-kafka, so we register one explicitly when no other
 * bean is provided.
 *
 * <p>{@code @ConditionalOnMissingBean} keeps user-supplied mappers (e.g. custom
 * modules) in charge.</p>
 */
@Configuration(proxyBeanMethods = false)
public class Jackson2ObjectMapperConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper();
    }
}
