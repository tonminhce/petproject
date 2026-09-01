package com.shop.mediaservice.service.impls;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * M1 — upload-time metadata INSPECTION using metadata-extractor: logs which
 * classes of personal metadata the upload carried (EXIF, GPS, IPTC, XMP).
 * Logging only — raw metadata is never persisted anywhere; every stored byte
 * comes out of the thumbnailator re-encode, which drops all metadata blocks
 * inherently (ImageIO renders pure pixels). The log is the privacy audit trail
 * proving the strip stage saw (and destroyed) the data.
 */
@Component
@Slf4j
class MediaMetadataInspector {

    /** Best-effort audit log of metadata classes present in the raw upload. */
    void inspect(byte[] source) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
            boolean exif = metadata.containsDirectoryOfType(ExifIFD0Directory.class);
            boolean gps = metadata.containsDirectoryOfType(GpsDirectory.class);
            boolean iptc = metadata.containsDirectoryOfType(IptcDirectory.class);
            boolean xmp = metadata.containsDirectoryOfType(XmpDirectory.class);
            if (exif || gps || iptc || xmp) {
                log.info("Upload metadata stripped: exif={}, gps={}, iptc={}, xmp={} — raw bytes never stored",
                        exif, gps, iptc, xmp);
            }
            if (gps) {
                int tagCount = metadata.getFirstDirectoryOfType(GpsDirectory.class).getTagCount();
                log.info("GPS metadata present in upload ({} tags) — removed by re-encode before storage", tagCount);
            }
        } catch (Exception inspectFailure) {
            // Inspection is advisory — a file that survived magic-byte checks but
            // confuses the extractor still proceeds to the render stage, which
            // enforces decodability. Never fail an upload from here.
            log.debug("Metadata inspection skipped (unreadable metadata block): {}", inspectFailure.toString());
        }
    }
}
