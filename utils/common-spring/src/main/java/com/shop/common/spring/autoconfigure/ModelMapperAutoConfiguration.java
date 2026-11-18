package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.mapping.RecordValueReader;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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