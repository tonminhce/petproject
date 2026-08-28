package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.mapping.RecordValueReader;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMapperAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModelMapperAutoConfiguration.class));

    @Test
    void registersModelMapperBeanWithRecordValueReader() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ModelMapper.class);
            ModelMapper mapper = ctx.getBean(ModelMapper.class);
            assertThat(mapper.getConfiguration().getValueReaders())
                .anySatisfy(r -> assertThat(r).isInstanceOf(RecordValueReader.class));
            assertThat(mapper.getConfiguration().isSkipNullEnabled()).isTrue();
            assertThat(mapper.getConfiguration().getMatchingStrategy()).isEqualTo(MatchingStrategies.STRICT);
        });
    }

    @Test
    void noModelMapperBeanWithoutAutoConfiguration() {
        new ApplicationContextRunner().run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ModelMapper.class);
        });
    }
}
