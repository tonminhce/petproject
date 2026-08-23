package com.shop.common.core.i18n;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges the {@link MessageSource} bean published by Spring into the static
 * {@link Messages} facade so exception constructors and utility classes can
 * resolve localized messages without depending on Spring DI directly.
 *
 * <p>Installed by {@code @EnableAutoConfiguration} on every service that depends
 * on {@code common-core}; the facade gracefully degrades to a classpath
 * {@code ResourceBundle} lookup when no {@link MessageSource} is present.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MessagesAutoConfiguration implements InitializingBean {

    private final MessageSource messageSource;

    public MessagesAutoConfiguration(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public void afterPropertiesSet() {
        Messages.setMessageSource(messageSource);
    }
}
