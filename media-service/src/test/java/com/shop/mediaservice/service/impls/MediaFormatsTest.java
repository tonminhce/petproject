package com.shop.mediaservice.service.impls;

import org.junit.jupiter.api.Test;

import static com.shop.mediaservice.service.impls.MediaFormats.formatOfContentType;
import static com.shop.mediaservice.service.impls.MediaFormats.sniff;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 allowlist + magic-byte model: the sniff matrix and the declared-type
 * mapping the pipeline orders into 415 (not allowed) vs 400 (corrupt/lying).
 */
class MediaFormatsTest {

    @Test
    void sniff_detectsJpegMagic() {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(sniff(head)).isEqualTo("jpeg");
    }

    @Test
    void sniff_detectsPngMagic() {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(sniff(head)).isEqualTo("png");
    }

    @Test
    void sniff_detectsWebpMagic() {
        byte[] head = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        assertThat(sniff(head)).isEqualTo("webp");
    }

    @Test
    void sniff_returnsNullForJunkShortHeadAndNull() {
        assertThat(sniff("this is not an image at all".getBytes())).isNull();
        assertThat(sniff(new byte[] {(byte) 0xFF, (byte) 0xD8})).isNull();
        assertThat(sniff(null)).isNull();
    }

    @Test
    void formatOfContentType_mapsAllowlist_andRejectsOthers() {
        assertThat(formatOfContentType("image/jpeg")).isEqualTo("jpeg");
        assertThat(formatOfContentType("image/jpg")).isEqualTo("jpeg");
        assertThat(formatOfContentType("image/PNG")).isEqualTo("png");
        assertThat(formatOfContentType("image/webp")).isEqualTo("webp");
        assertThat(formatOfContentType("image/gif")).isNull();
        assertThat(formatOfContentType("text/plain")).isNull();
        assertThat(formatOfContentType(null)).isNull();
    }

    @Test
    void contentTypeAndExtensionMappings_areCompleteForTheAllowlist() {
        assertThat(MediaFormats.ALLOWED_CONTENT_TYPES).containsExactlyInAnyOrder(
                "image/jpeg", "image/png", "image/webp");
        assertThat(MediaFormats.contentTypeOf("jpeg")).isEqualTo("image/jpeg");
        assertThat(MediaFormats.contentTypeOf("png")).isEqualTo("image/png");
        assertThat(MediaFormats.contentTypeOf("webp")).isEqualTo("image/webp");
        assertThat(MediaFormats.extOf("jpeg")).isEqualTo("jpg");
        assertThat(MediaFormats.extOf("png")).isEqualTo("png");
        assertThat(MediaFormats.extOf("webp")).isEqualTo("webp");
    }
}
