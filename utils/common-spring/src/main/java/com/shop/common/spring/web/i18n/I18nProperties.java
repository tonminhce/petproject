package com.shop.common.spring.web.i18n;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Configuration for the platform's i18n setup, bound from {@code shop.i18n.*}.
 *
 * <pre>{@code
 * shop:
 *   i18n:
 *     default-locale: vi
 *     fallback-to-system-locale: true
 *     use-code-as-default-message: false
 *     encoding: UTF-8
 *     cache-duration: 5m
 *     basenames: classpath:messages/messages
 * }</pre>
 */
@ConfigurationProperties(prefix = "shop.i18n")
public class I18nProperties {

    /** Default locale used when the request doesn't send {@code Accept-Language}. */
    private Locale defaultLocale = Locale.forLanguageTag("vi");

    /** When {@code true}, fall back to {@link Locale#getDefault()} if no message bundle matches. */
    private boolean fallbackToSystemLocale = true;

    /** When {@code true}, the message key is returned as-is when no translation is found. */
    private boolean useCodeAsDefaultMessage = false;

    /** Bundle file encoding. */
    private Charset encoding = StandardCharsets.UTF_8;

    /** Reloadable bundle cache duration. Set to {@code 0} to disable caching. */
    private Duration cacheDuration = Duration.ofMinutes(5);

    /** Comma-separated basename list. Each basename resolves to multiple {@code _locale} files. */
    private List<String> basenames = List.of("classpath:messages/messages");

    public Locale getDefaultLocale() { return defaultLocale; }
    public void setDefaultLocale(Locale defaultLocale) { this.defaultLocale = defaultLocale; }

    public boolean isFallbackToSystemLocale() { return fallbackToSystemLocale; }
    public void setFallbackToSystemLocale(boolean fallbackToSystemLocale) {
        this.fallbackToSystemLocale = fallbackToSystemLocale;
    }

    public boolean isUseCodeAsDefaultMessage() { return useCodeAsDefaultMessage; }
    public void setUseCodeAsDefaultMessage(boolean useCodeAsDefaultMessage) {
        this.useCodeAsDefaultMessage = useCodeAsDefaultMessage;
    }

    public Charset getEncoding() { return encoding; }
    public void setEncoding(Charset encoding) { this.encoding = encoding; }

    public Duration getCacheDuration() { return cacheDuration; }
    public void setCacheDuration(Duration cacheDuration) { this.cacheDuration = cacheDuration; }

    public List<String> getBasenames() { return basenames; }
    public void setBasenames(List<String> basenames) { this.basenames = basenames; }
}