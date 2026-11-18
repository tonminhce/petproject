package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantRendererMemoryBudgetTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    private VariantRenderer renderer(final long budgetBytes) {
        MediaProperties properties = new MediaProperties(
                "media", Duration.ofDays(7), DataSize.ofMegabytes(10), Duration.ofDays(30),
                1200, 320, DataSize.ofBytes(budgetBytes));
        return new VariantRenderer(properties);
    }

    private long decodedBytes(final byte[] source) {

        return (long) WIDTH * HEIGHT * 4L + source.length;
    }

    @Test
    @DisplayName("an image within the budget is rendered")
    void anImageWithinTheBudgetIsRendered() throws Exception {
        byte[] source = TestImages.jpeg(WIDTH, HEIGHT);

        var renders = renderer(decodedBytes(source) + 1)
                .render(source, "jpeg", 1200, 320);

        assertThat(renders).isNotEmpty();
    }

    @Test
    @DisplayName("an image above the budget is rejected BEFORE it is decoded")
    void anImageAboveTheBudgetIsRejected() {
        byte[] source = TestImages.jpeg(WIDTH, HEIGHT);

        assertThatThrownBy(() -> renderer(decodedBytes(source) - 1)
                .render(source, "jpeg", 1200, 320))
                .isInstanceOf(VariantRenderer.ImageTooLargeException.class)
                .hasMessageContaining("budget");
    }

    @Test
    @DisplayName("the source bytes count towards the budget")
    void theSourceBytesCountTowardsTheBudget() {
        byte[] source = TestImages.jpeg(WIDTH, HEIGHT);

        long pixelsOnly = (long) WIDTH * HEIGHT * 4L;

        assertThatThrownBy(() -> renderer(pixelsOnly)
                .render(source, "jpeg", 1200, 320))
                .isInstanceOf(VariantRenderer.ImageTooLargeException.class);
    }
}
