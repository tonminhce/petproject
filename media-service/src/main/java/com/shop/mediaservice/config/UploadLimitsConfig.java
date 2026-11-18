package com.shop.mediaservice.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration(proxyBeanMethods = false)
public class UploadLimitsConfig {

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
