package com.shop.productservice.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-2 — every product-local message key surfaced by the media-reference
 * semantics must exist in BOTH bundles (common-spring is on the classpath as
 * a dependency jar). Pins the {@code product.media.clear.conflict} binding
 * error (clear=true together with a mediaId) so neither locale ever renders
 * the raw template key.
 */
class ProductI18nKeysTest {

    private static final List<String> KEYS = List.of(
            "product.media.clear.conflict");

    private static final String[] BUNDLES = {
            "/messages/messages_en.properties",
            "/messages/messages_vi.properties"
    };

    @Test
    void bothBundles_containAllProductMediaKeys() throws Exception {
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
