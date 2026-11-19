package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
@RequiredArgsConstructor
class VariantRenderer {

    private static final int MAX_DIMENSION = 8192;

    /** Bytes per pixel of a decoded {@code BufferedImage} (ARGB). */
    private static final long BYTES_PER_PIXEL = 4L;

    private final MediaProperties properties;

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

    /**
     * Decodes the upload ONCE.
     *
     * <p>F-062: the source used to be parsed twice - once by the dimension
     * probe and once more by {@code ImageIO.read}, which opened its own
     * stream over the same bytes. The probe and the decode now share one
     * {@code ImageInputStream} and one {@code ImageReader}.</p>
     *
     * <p>The guard is expressed in DECODED MEMORY, not in pixels: a
     * {@code BufferedImage} of width x height costs 4 bytes per pixel, and
     * the source bytes are held at the same time. An image above the budget
     * is rejected BEFORE it is decoded, so the peak heap of the upload stays
     * bounded instead of exhausting the heap (the old pixel guard allowed
     * 8192 x 8192 = 268 MB for a single upload).</p>
     */
    private BufferedImage decode(byte[] source) throws InvalidImageException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (in == null) {
                throw new InvalidImageException("Unable to create image input stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidImageException("No ImageIO reader could decode the upload");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new InvalidImageException("Image dimensions exceed maximum allowed limits: "
                            + width + "x" + height);
                }
                long decodedBytes = (long) width * height * BYTES_PER_PIXEL;
                long budget = properties.maxDecodedHeapBytes();
                if (decodedBytes + source.length > budget) {
                    throw new ImageTooLargeException("Decoded image (" + width + "x" + height + " = "
                            + decodedBytes + " bytes) plus the source (" + source.length
                            + " bytes) exceeds the budget of " + budget + " bytes");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("No ImageIO reader could decode the upload");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
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
            thumbnail.imageType(BufferedImage.TYPE_INT_RGB);
        }
        thumbnail.outputFormat(format).outputQuality(0.9).toOutputStream(out);
        return out.toByteArray();
    }

    /** Corrupt upload that passed the magic check but cannot be decoded/rendered. */
    static class InvalidImageException extends Exception {
        InvalidImageException(String message) {
            super(message);
        }

        InvalidImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The upload is a valid image, but decoding it would exceed the memory
     * budget of the service (F-062) - a 413, not a 415.
     */
    static final class ImageTooLargeException extends InvalidImageException {
        ImageTooLargeException(String message) {
            super(message);
        }
    }
}
