package com.shop.common.core.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class Messages {

    private static final String BUNDLE_BASENAME = "messages.messages";

    private static volatile MessageSource messageSource;

    private Messages() {
    }

    public static void setMessageSource(MessageSource source) {
        messageSource = source;
    }

    public static MessageSource getMessageSource() {
        return messageSource;
    }

    public static String get(String messageKey, Object... args) {
        if (messageKey == null) {
            return null;
        }
        Locale locale = LocaleContextHolder.getLocale();

        MessageSource source = messageSource;
        if (source != null) {
            try {
                return source.getMessage(messageKey, args, locale);
            } catch (NoSuchMessageException ignored) {
            }
        }

        return fromBundle(messageKey, locale, args);
    }

    private static String fromBundle(String messageKey, Locale locale, Object[] args) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASENAME, locale);
            String template = bundle.getString(messageKey);
            return args == null || args.length == 0
                    ? template
                    : new MessageFormat(template, locale).format(args);
        } catch (MissingResourceException ex) {
            return messageKey;
        }
    }
}
