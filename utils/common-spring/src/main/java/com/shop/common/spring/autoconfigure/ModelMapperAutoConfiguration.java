package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.mapping.RecordValueReader;
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
 * <p>Also registers a {@link RecordValueReader} so request DTOs that are
 * Java records (canonical accessors like {@code title()}, not JavaBean-style
 * {@code getTitle()}) work as mapping sources — without it,
 * {@code setFieldMatchingEnabled(true)} leaves record-to-entity mapping
 * silently empty.</p>
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
                .setFieldMatchingEnabled(true)
                .addValueReader(new RecordValueReader());
        return mapper;
    }
}