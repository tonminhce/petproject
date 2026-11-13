package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-064: the rendered object count per source format.
 *
 * <p>The documentation claimed "6 WebP variants". The renderer produces
 * THREE variants (original/display/thumb), each stored in the SOURCE format
 * and in WebP - so a JPEG or PNG upload yields SIX objects, of which only
 * three are WebP, and a WebP upload yields THREE objects (the two formats
 * coincide, so no duplicate variant+format row is written).</p>
 */
class VariantRendererObjectCountTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final long BUDGET = 64L * 1024 * 1024;

    private VariantRenderer renderer() {
        MediaProperties properties = new MediaProperties(
                "media", Duration.ofDays(7), DataSize.ofMegabytes(10), Duration.ofDays(30),
                1200, 320, DataSize.ofBytes(BUDGET));
        return new VariantRenderer(properties);
    }

    @Test
    @DisplayName("a JPEG upload yields 6 objects - 3 variants in the source format and in WebP")
    void aJpegUploadYieldsSixObjects() throws Exception {
        byte[] source = TestImages.jpeg(WIDTH, HEIGHT);

        List<VariantRenderer.Render> renders = renderer().render(source, "jpeg", 1200, 320);

        assertThat(renders).hasSize(6);
        assertThat(renders).extracting(VariantRenderer.Render::variant)
                .containsExactlyInAnyOrder("original", "original", "display", "display", "thumb", "thumb");
        assertThat(renders).filteredOn(r -> "webp".equals(r.format())).hasSize(3);
        assertThat(renders).filteredOn(r -> "jpeg".equals(r.format())).hasSize(3);
    }

    @Test
    @DisplayName("a WebP upload yields 3 objects - the source format and WebP coincide")
    void aWebpUploadYieldsThreeObjects() throws Exception {
        byte[] source = TestImages.webp(WIDTH, HEIGHT);

        List<VariantRenderer.Render> renders = renderer().render(source, "webp", 1200, 320);

        assertThat(renders).hasSize(3);
        assertThat(renders).extracting(VariantRenderer.Render::variant)
                .containsExactlyInAnyOrder("original", "display", "thumb");
        assertThat(renders).extracting(VariantRenderer.Render::format).containsOnly("webp");
    }

    @Test
    @DisplayName("no variant+format row is written twice")
    void noVariantFormatRowIsWrittenTwice() throws Exception {
        byte[] source = TestImages.png(WIDTH, HEIGHT);

        List<VariantRenderer.Render> renders = renderer().render(source, "png", 1200, 320);

        assertThat(renders)
                .extracting(r -> r.variant() + "/" + r.format())
                .doesNotHaveDuplicates();
    }
}
