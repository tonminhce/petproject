package com.shop.common.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers a classic Jackson 2 {@link ObjectMapper} bean for services whose
 * other platform modules (e.g. {@code common-kafka} via
 * {@code com.shop.common.kafka.serialization.JsonKafkaSerializer}) consume
 * Jackson 2, even though Spring Boot 4's autoconfig ships Jackson 3 by
 * default.
 *
 * <p>{@code @ConditionalOnMissingBean} keeps any user-supplied mapper (custom
 * modules, etc.) in charge. Module only auto-activates when Jackson 2 is
 * actually on the classpath — services that already pulled in Jackson 3 only
 * skip this and keep Boot's default.</p>
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class Jackson2ObjectMapperAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper();
    }
}
