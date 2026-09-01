package com.shop.mediaservice.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Deterministic image fixtures for the upload ITs. The EXIF splicer builds a
 * minimal but fully valid EXIF APP1 segment (IFD0 with Make + GPS IFD with
 * GPSLatitudeRef) and splices it into an ImageIO-encoded JPEG — metadata-extractor
 * reads it back as genuine EXIF + GPS, which is what the strip-proof test
 * relies on.
 */
public final class TestImages {

    private TestImages() {
    }

    /** Opaque RGB test pattern (gradient — compresses to non-trivial sizes). */
    public static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = 0; x < width; x += 8) {
            g.setColor(new Color((x * 7) % 256, (x * 13) % 256, (x * 29) % 256));
            g.fillRect(x, 0, 8, height);
        }
        g.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(image(width, height), "png");
    }

    public static byte[] webp(int width, int height) {
        return encode(image(width, height), "webp");
    }

    public static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JPEG whose magic + metadata survive, padded with zeros past the cap. */
    public static byte[] jpegMagicOfSize(long totalSize) {
        byte[] head = Arrays.copyOf(jpeg(4, 4), (int) totalSize);
        return head;
    }

    /** Bytes matching no allowed magic — declared as any image type → corrupt. */
    public static byte[] nonImageBytes() {
        byte[] junk = new byte[256];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) ('a' + (i % 26));
        }
        return junk;
    }

    /**
     * JPEG carrying a valid EXIF APP1 (IFD0: Make="TestCam") and a GPS IFD
     * (GPSLatitudeRef="N"). Sanity-check with metadata-extractor before use.
     */
    public static byte[] jpegWithExifAndGps(int width, int height) {
        byte[] plain = jpeg(width, height);
        byte[] app1 = exifApp1Segment();

        byte[] spliced = new byte[plain.length + app1.length];
        System.arraycopy(plain, 0, spliced, 0, 2);          // SOI
        System.arraycopy(app1, 0, spliced, 2, app1.length); // EXIF APP1
        System.arraycopy(plain, 2, spliced, 2 + app1.length, plain.length - 2);
        return spliced;
    }

    /**
     * Layout (little-endian TIFF, offsets relative to TIFF start):
     * <pre>
     *  0: "II" 2A 00 08 00 00 00          header, IFD0 @ 8
     *  8: IFD0  count=2
     *     Make       tag 010F ascii  8  -> 38 ("TestCam\0")
     *     GPSInfo    tag 8825 long   1  -> 46 (GPS IFD offset)
     *     nextIFD=0
     * 38: "TestCam\0"
     * 46: GPS IFD count=1
     *     GPSLatitudeRef tag 0001 ascii 2 -> "N\0" (inline)
     *     nextIFD=0
     * </pre>
     */
    private static byte[] exifApp1Segment() {
        byte[] tiff = new byte[64];
        tiff[0] = 'I'; tiff[1] = 'I';                       // little-endian
        tiff[2] = 0x2A; tiff[3] = 0x00;                     // TIFF magic 42
        tiff[4] = 0x08;                                     // IFD0 @ 8
        tiff[8] = 0x02; tiff[9] = 0x00;                     // 2 entries

        // entry: Make (0x010F), ASCII, count 8, value @ 38
        writeEntry(tiff, 10, 0x010F, 2, 8, 38);
        // entry: GPS Info pointer (0x8825), LONG, count 1, value = 46
        writeEntry(tiff, 22, 0x8825, 4, 1, 46);
        // next IFD = 0 (bytes 34..37 already zero)

        byte[] make = "TestCam\0".getBytes();
        System.arraycopy(make, 0, tiff, 38, make.length);   // 8 bytes -> offset 46

        tiff[46] = 0x01; tiff[47] = 0x00;                   // GPS IFD: 1 entry
        // entry: GPSLatitudeRef (0x0001), ASCII, count 2, inline "N\0"
        writeEntry(tiff, 48, 0x0001, 2, 2, ('N' | ('\0' << 8)));
        // next IFD = 0 (bytes 60..63 already zero)

        ByteArrayOutputStream segment = new ByteArrayOutputStream(6 + tiff.length);
        segment.write(0xFF); segment.write(0xE1);           // APP1 marker
        int segmentLength = 2 + 6 + tiff.length;            // length field counts itself
        segment.write((segmentLength >> 8) & 0xFF);
        segment.write(segmentLength & 0xFF);
        segment.writeBytes("Exif\0\0".getBytes());          // APP1 payload header
        segment.writeBytes(tiff);
        return segment.toByteArray();
    }

    private static void writeEntry(byte[] tiff, int at, int tag, int type, long count, int value) {
        tiff[at] = (byte) tag;         tiff[at + 1] = (byte) (tag >> 8);
        tiff[at + 2] = (byte) type;    tiff[at + 3] = (byte) (type >> 8);
        for (int i = 0; i < 4; i++) {
            tiff[at + 4 + i] = (byte) (count >> (8 * i));
        }
        for (int i = 0; i < 4; i++) {
            tiff[at + 8 + i] = (byte) (value >> (8 * i));
        }
    }
}
