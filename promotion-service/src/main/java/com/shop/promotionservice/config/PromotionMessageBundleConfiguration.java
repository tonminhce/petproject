package com.shop.promotionservice.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractResourceBasedMessageSource;

@Configuration
public class PromotionMessageBundleConfiguration {

    private static final String PROMOTION_BASENAME = "classpath:messages/promotion";

    @Bean
    static BeanPostProcessor promotionMessageBundle() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractResourceBasedMessageSource source) {
                    source.addBasenames(PROMOTION_BASENAME);
                }
                return bean;
            }
        };
    }
}
