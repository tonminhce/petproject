package com.shop.mediaservice.service.impls;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * D2 render stage — thumbnailator over the DECODED pixels only. The source is
 * decoded with ImageIO (which reads no metadata blocks into the image) and
 * every output is a fresh encode, so the stored original is a FULL-RESOLUTION
 * re-encode — never the raw upload bytes — and EXIF/GPS is gone by
 * construction (M1). Six renders per upload: original/display/thumb × the
 * original format AND WebP; display/thumb cap width and never upscale.
 */
@Component
class VariantRenderer {

    /** One rendered variant pending its S3 write. */
    record Render(String variant, String format, int width, byte[] bytes) {
    }

    /**
     * Decode + render the D2 variants — original/display/thumb, each in the
     * original format AND WebP. For WebP sources the two formats coincide, so
     * the pair collapses and the render list holds 3 entries (never duplicate
     * variant+format rows).
     *
     * @throws InvalidImageException when the bytes (magic-valid or not) cannot
     *                               be decoded into an image — corrupt upload
     * @throws IllegalStateException on an encoder failure of an ALREADY-DECODED
     *                               image — a 500-class internal error, not a
     *                               user-input problem (input is always a
     *                               {@code BufferedImage}, outputs always
     *                               supported formats; wrapped once here
     *                               instead of per-encode)
     */
    List<Render> render(byte[] source, String format, int displayWidthCap, int thumbWidthCap)
            throws InvalidImageException {
        BufferedImage image = decode(source);
        List<Render> renders = new ArrayList<>(6);
        try {
            for (String variant : new String[] {"original", "display", "thumb"}) {
                int width;
                int height;
                if ("original".equals(variant)) {
                    width = image.getWidth();
                    height = image.getHeight();
                } else {
                    int cap = "display".equals(variant) ? displayWidthCap : thumbWidthCap;
                    int[] dims = fitWithin(image, cap);
                    width = dims[0];
                    height = dims[1];
                }
                renders.add(new Render(variant, format, width, encode(image, width, height, format)));
                if (!MediaFormats.WEBP.equals(format)) {
                    renders.add(new Render(variant, MediaFormats.WEBP, width,
                            encode(image, width, height, MediaFormats.WEBP)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Variant encode failed", e);
        }
        return renders;
    }

    private BufferedImage decode(byte[] source) throws InvalidImageException {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                throw new InvalidImageException("No ImageIO reader could decode the upload");
            }
            return image;
        } catch (IOException e) {
            // reachable: magic-valid heads whose bodies the claimed codec
            // rejects (e.g. a bogus segment length) surface as IIOException
            throw new InvalidImageException("Truncated or unreadable image stream", e);
        }
    }

    /**
     * Downscale-only width cap: an image narrower than the cap keeps its
     * dimensions (no upscaling), a wider one is scaled to the cap keeping
     * aspect ratio.
     */
    private static int[] fitWithin(BufferedImage image, int widthCap) {
        if (image.getWidth() <= widthCap) {
            return new int[] {image.getWidth(), image.getHeight()};
        }
        int height = Math.max(1, Math.round(image.getHeight() * (widthCap / (float) image.getWidth())));
        return new int[] {widthCap, height};
    }

    private byte[] encode(BufferedImage image, int width, int height, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        var thumbnail = Thumbnails.of(image).size(width, height);
        if (MediaFormats.JPEG.equals(format)) {
            // JPEG cannot carry alpha — flatten first so transparency
            // doesn't render black.
            thumbnail.imageType(BufferedImage.TYPE_INT_RGB);
        }
        thumbnail.outputFormat(format).outputQuality(0.9).toOutputStream(out);
        return out.toByteArray();
    }

    /** Corrupt upload that passed the magic check but cannot be decoded/rendered. */
    static final class InvalidImageException extends Exception {
        InvalidImageException(String message) {
            super(message);
        }

        InvalidImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
