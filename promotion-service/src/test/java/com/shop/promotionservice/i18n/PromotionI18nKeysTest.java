package com.shop.promotionservice.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionI18nKeysTest {

    private static final List<String> KEYS = List.of(
            "promotion.currency.mismatch",
            "promotion.currency.unsupported");

    private static final String[] BUNDLES = {
            "/messages/promotion_en.properties",
            "/messages/promotion_vi.properties"
    };

    @Test
    void bothBundles_containAllPromotionCurrencyKeys() throws Exception {
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
