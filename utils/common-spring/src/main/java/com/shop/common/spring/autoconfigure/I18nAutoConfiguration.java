package com.shop.common.spring.autoconfigure;

import com.shop.common.core.i18n.Messages;
import com.shop.common.spring.web.i18n.I18nProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@AutoConfiguration
@EnableConfigurationProperties(I18nProperties.class)
public class I18nAutoConfiguration {

    private final I18nProperties props;

    public I18nAutoConfiguration(I18nProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(MessageSource.class)
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(props.getBasenames().toArray(new String[0]));
        source.setDefaultEncoding(props.getEncoding().name());
        source.setDefaultLocale(props.getDefaultLocale());
        source.setFallbackToSystemLocale(props.isFallbackToSystemLocale());
        source.setUseCodeAsDefaultMessage(props.isUseCodeAsDefaultMessage());
        source.setCacheMillis(props.getCacheDuration().toMillis());
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(LocaleResolver.class)
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(props.getDefaultLocale());
        resolver.setSupportedLocales(List.of(props.getDefaultLocale(), Locale.ENGLISH));
        return resolver;
    }

    @Bean
    public SmartInitializingSingleton i18nStaticBridge(MessageSource messageSource) {
        return () -> Messages.setMessageSource(messageSource);
    }
}
