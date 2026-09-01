package com.shop.mediaservice.service.impls;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * D1 format model — the mime allowlist, canonical format names
 * ({@code jpeg|png|webp}), file extensions, and magic-byte sniffing. Sniffing
 * is the source of truth: a declared type that the magic bytes contradict is
 * a corrupt upload (MED-12001), a declared type outside the allowlist is a
 * rejected type (MED-12003) — the pipeline in {@link MediaUploadServiceImpl}
 * applies that ordering.
 */
final class MediaFormats {

    static final String JPEG = "jpeg";
    static final String PNG = "png";
    static final String WEBP = "webp";

    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private static final Map<String, String> CONTENT_TYPES =
            Map.of(JPEG, "image/jpeg", PNG, "image/png", WEBP, "image/webp");
    private static final Map<String, String> EXTENSIONS =
            Map.of(JPEG, "jpg", PNG, "png", WEBP, "webp");

    private MediaFormats() {
    }

    /** MIME type of a canonical format, e.g. {@code jpeg -> image/jpeg}. */
    static String contentTypeOf(String format) {
        return CONTENT_TYPES.get(format);
    }

    /** Object-key extension of a canonical format, e.g. {@code jpeg -> jpg}. */
    static String extOf(String format) {
        return EXTENSIONS.get(format);
    }

    /**
     * Canonical format name for a declared MIME type (lower-cased subtype,
     * e.g. {@code image/jpeg -> jpeg}) or {@code null} when not allowed.
     */
    static String formatOfContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "image/jpeg", "image/jpg" -> JPEG;
            case "image/png" -> PNG;
            case "image/webp" -> WEBP;
            default -> null;
        };
    }

    /**
     * Magic-byte sniff of the file head — {@code jpeg|png|webp}, or
     * {@code null} when the bytes match no allowed format (corrupt file).
     */
    static String sniff(byte[] head) {
        if (head == null || head.length < 12) {
            return null;
        }
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return PNG;
        }
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return WEBP;
        }
        return null;
    }
}
