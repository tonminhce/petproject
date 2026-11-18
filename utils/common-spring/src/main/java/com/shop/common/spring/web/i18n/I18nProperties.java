package com.shop.common.spring.web.i18n;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "shop.i18n")
public class I18nProperties {

    private Locale defaultLocale = Locale.forLanguageTag("vi");

    private boolean fallbackToSystemLocale = true;

    private boolean useCodeAsDefaultMessage = false;

    private Charset encoding = StandardCharsets.UTF_8;

    private Duration cacheDuration = Duration.ofMinutes(5);

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