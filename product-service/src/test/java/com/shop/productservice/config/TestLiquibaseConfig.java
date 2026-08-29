package com.shop.productservice.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NOTE: deliberately NO {@code @EnableJpaAuditing} here — tests that need it
 * {@code @Import} the platform's {@code JpaAuditingAutoConfiguration}, which is
 * the single sanctioned home of that annotation. Duplicating it here registers
 * {@code jpaAuditingHandler} twice and fails the context with a
 * BeanDefinitionOverrideException.
 */
@Configuration(proxyBeanMethods = false)
public class TestLiquibaseConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringLiquibase springLiquibase(DataSource dataSource,
                                           @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        return liquibase;
    }
}