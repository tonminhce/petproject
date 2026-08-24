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