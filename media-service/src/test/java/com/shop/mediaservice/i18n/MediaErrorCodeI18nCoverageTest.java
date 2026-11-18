package com.shop.mediaservice.i18n;

import com.shop.common.core.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MediaErrorCodeI18nCoverageTest {

    private static final List<String> EXPECTED_MEDIA_CODES = List.of(
            "MED-12001",
            "MED-12002",
            "MED-12003",
            "MED-12004",
            "MED-12005",
            "MED-12006",
            "MED-12007");

    private static final String[] BUNDLES = {
            "/messages/messages_en.properties",
            "/messages/messages_vi.properties"
    };

    @Test
    @DisplayName("every MED-* enum value (derived) has a non-blank key in BOTH bundles")
    void everyMediaCode_hasNonBlankKeyInBothBundles() throws Exception {
        List<String> derived = java.util.Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .filter(code -> code.startsWith("MED-"))
                .toList();

        assertThat(derived)
                .as("the MED-* family must be discovered from the enum")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MEDIA_CODES);
        assertThat(derived).as("the media family must not be empty").isNotEmpty();

        for (String bundle : BUNDLES) {
            Properties props = load(bundle);
            for (String code : derived) {
                String key = ErrorCode.valueOf(enumNameOf(code)).getMessageKey();
                assertThat(key).as("enum key mapping for %s", code).isNotBlank();
                assertThat(props.getProperty(key))
                        .as("key %s (code %s) in %s", key, code, bundle)
                        .isNotBlank();
            }
        }
    }

    private static String enumNameOf(String code) {
        return java.util.Arrays.stream(ErrorCode.values())
                .filter(c -> c.getCode().equals(code))
                .findFirst()
                .orElseThrow()
                .name();
    }

    private static Properties load(String bundle) throws Exception {
        Properties props = new Properties();
        try (InputStream in = MediaErrorCodeI18nCoverageTest.class.getResourceAsStream(bundle)) {
            assertThat(in).as("bundle %s on classpath", bundle).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
