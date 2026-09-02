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

/**
 * FLEET REFERENCE PATTERN — the ErrorCode↔i18n linkage test. Derived, not
 * hand-maintained: EVERY media-service ErrorCode value (MED-* prefix in the
 * shared {@link ErrorCode} enum, discovered via reflection) must resolve to a
 * NON-BLANK message key in BOTH bundles (EN + VI, served from the
 * common-spring jar). Unlike a hardcoded key list, this FAILS AT BIRTH when a
 * new MED-* code is added without its bundle entries — add the key to
 * {@code utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties}
 * and extend the pin list here in the same commit.
 *
 * <p>Other services should clone this shape for their own code prefixes
 * (RTG-*, INV-*, …): one test per service, derived from the enum, both
 * bundles asserted.</p>
 */
class MediaErrorCodeI18nCoverageTest {

    /** All media-service codes — kept as a pin so renames/removals are caught too. */
    private static final List<String> EXPECTED_MEDIA_CODES = List.of(
            "MED-12001", // MEDIA_INVALID_FILE
            "MED-12002", // MEDIA_TOO_LARGE
            "MED-12003", // MEDIA_TYPE_NOT_ALLOWED
            "MED-12004", // MEDIA_NOT_FOUND
            "MED-12005", // MEDIA_ALREADY_DELETED
            "MED-12006"); // MEDIA_STORAGE_UNAVAILABLE

    private static final String[] BUNDLES = {
            "/messages/messages_en.properties",
            "/messages/messages_vi.properties"
    };

    @Test
    @DisplayName("every MED-* enum value (derived) has a non-blank key in BOTH bundles")
    void everyMediaCode_hasNonBlankKeyInBothBundles() throws Exception {
        // derive the media family from the shared enum by prefix
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
