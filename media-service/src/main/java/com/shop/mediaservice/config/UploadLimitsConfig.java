package com.shop.mediaservice.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * D1 — the servlet multipart ceiling is DERIVED from {@code media.max-upload},
 * never configured independently: it sits one megabyte above the business cap
 * so Spring's parser never fires first — oversize uploads reach the pipeline's
 * own size guard and surface as 413 MED-12002 (D6), not the generic ERR-0413.
 * Defining this bean also backs Boot's {@code MultipartAutoConfiguration} off
 * so no {@code spring.servlet.multipart.*} drift can reintroduce a second cap.
 */
@Configuration(proxyBeanMethods = false)
public class UploadLimitsConfig {

    /** Headroom above the business cap before the servlet parser hard-stops. */
    static final long SERVLET_HEADROOM_BYTES = 1024L * 1024L;

    @Bean
    public MultipartConfigElement multipartConfigElement(final MediaProperties properties) {
        final long cap = properties.maxUploadBytes();
        return new MultipartConfigElement(
                Paths.get(System.getProperty("java.io.tmpdir"), "media-upload").toString(),
                cap + SERVLET_HEADROOM_BYTES,
                cap + 2 * SERVLET_HEADROOM_BYTES,
                512 * 1024);
    }
}
