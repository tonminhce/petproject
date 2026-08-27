package com.shop.common.spring.autoconfigure;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-wires a singleton {@link ModelMapper} bean for any service that depends on
 * the ModelMapper library. Configured with STRICT matching + skip-null to match the
 * platform's "PATCH = only update non-null fields" semantics.
 *
 * <p><strong>Java record support caveat:</strong> ModelMapper 3.2.6 in STRICT +
 * field-matching mode does not see Java record components as source properties
 * — only the canonical accessor methods (e.g. {@code title()}, not the private
 * field {@code title}). The working fix is per-mapper manual setter copy
 * (see {@code product-service/.../ProductMapper.toEntity}). A platform-level
 * fix via a custom {@code ValueReader} is tracked as a follow-up — the
 * ModelMapper 3 SPI requires a non-trivial adapter that should be designed
 * carefully rather than rushed here.</p>
 */
@AutoConfiguration
@ConditionalOnClass(ModelMapper.class)
public class ModelMapperAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelMapper.class)
    public ModelMapper modelMapper() {
        ModelMapper mapper = new ModelMapper();
        mapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setSkipNullEnabled(true)
                .setFieldMatchingEnabled(true);
        return mapper;
    }
}