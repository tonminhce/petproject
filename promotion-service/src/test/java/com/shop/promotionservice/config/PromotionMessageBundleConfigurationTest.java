package com.shop.promotionservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionMessageBundleConfigurationTest {

    @Test
    void thePromotionBundleIsRegisteredOnTheMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages/messages");
        source.setResourceLoader(new DefaultResourceLoader());

        PromotionMessageBundleConfiguration.promotionMessageBundle()
            .postProcessAfterInitialization(source, "messageSource");

        assertThat(source.getBasenameSet()).contains("classpath:messages/promotion");
    }

    @Test
    void thePromotionCurrencyMessagesAreResolvedFromThePromotionBundle() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages/messages");
        source.setResourceLoader(new DefaultResourceLoader());

        PromotionMessageBundleConfiguration.promotionMessageBundle()
            .postProcessAfterInitialization(source, "messageSource");

        assertThat(source.getMessage("promotion.currency.mismatch", new Object[] {"USD", "VND"}, Locale.ENGLISH))
            .isEqualTo("Promotion currency USD does not match the campaign currency VND");
        assertThat(source.getMessage("promotion.currency.unsupported", new Object[] {"EUR"}, Locale.ENGLISH))
            .isEqualTo("Promotion currency EUR is not supported");
    }
}
