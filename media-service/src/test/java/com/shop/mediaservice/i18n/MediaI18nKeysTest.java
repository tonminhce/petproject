package com.shop.mediaservice.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec D6 — every MED-12xxx message key this task's pipeline can surface
 * (invalid/too-large/type-not-allowed/storage-unavailable) must exist in BOTH
 * bundles (common-spring is on the classpath as a dependency jar).
 */
class MediaI18nKeysTest {

    private static final List<String> KEYS = List.of(
            "media.invalid_file",
            "media.too_large",
            "media.type_not_allowed",
            "media.storage_unavailable");

    private static final String[] BUNDLES = {
            "/messages/messages_en.properties",
            "/messages/messages_vi.properties"
    };

    @Test
    void bothBundles_containAllMediaKeys() throws Exception {
        for (String bundle : BUNDLES) {
            Properties props = new Properties();
            try (InputStream in = getClass().getResourceAsStream(bundle)) {
                assertThat(in).as("bundle %s on classpath", bundle).isNotNull();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : KEYS) {
                assertThat(props.getProperty(key))
                        .as("key %s in %s", key, bundle)
                        .isNotBlank();
            }
        }
    }
}
