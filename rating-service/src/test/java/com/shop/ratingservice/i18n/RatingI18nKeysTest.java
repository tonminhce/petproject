package com.shop.ratingservice.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec D7 — all five RTG-11xxx message keys must exist in BOTH bundles
 * (common-spring is on the classpath as a dependency jar).
 */
class RatingI18nKeysTest {

    private static final List<String> KEYS = List.of(
            "rating.not_eligible",
            "rating.not_found",
            "rating.already_hidden",
            "rating.not_hidden",
            "rating.already_exists");

    private static final String[] BUNDLES = {
            "/messages/messages_en.properties",
            "/messages/messages_vi.properties"
    };

    @Test
    void bothBundles_containAllRatingKeys() throws Exception {
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
