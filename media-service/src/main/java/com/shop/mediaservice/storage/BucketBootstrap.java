package com.shop.mediaservice.storage;

import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Type;

/**
 * D1 — startup bootstrap of the PRIVATE media bucket. Creates the bucket when
 * missing (idempotent) and then asserts the ACL carries no public grant; a
 * bucket that exposes media objects is a hard misconfiguration, so a failed
 * assert refuses to bless it (the error surfaces in the log and health stays
 * degraded). Mirrors search's IndexProvisioner tolerance: a storage outage at
 * startup is logged, not fatal — the service boots and recovers once the
 * object store is reachable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BucketBootstrap implements ApplicationRunner {

    private final ObjectStorageService storage;
    private final S3Client s3Client;
    private final MediaProperties properties;

    @Override
    public void run(final ApplicationArguments args) {
        try {
            bootstrap();
        } catch (Exception e) {
            log.error("Bucket bootstrap failed — media storage stays degraded until object storage is reachable", e);
        }
    }

    void bootstrap() {
        storage.ensureBucketExists(properties.bucket());
        assertPrivateAcl(properties.bucket());
        log.info("Bucket '{}' provisioned with private ACL", properties.bucket());
    }

    private void assertPrivateAcl(final String bucket) {
        final boolean publicGrant = s3Client.getBucketAcl(b -> b.bucket(bucket)).grants().stream()
                .anyMatch(BucketBootstrap::isPublicGrant);
        if (publicGrant) {
            throw new IllegalStateException(
                    "Bucket '" + bucket + "' carries public ACL grants — refusing to serve private media from it");
        }
    }

    private static boolean isPublicGrant(final Grant grant) {
        final Grantee grantee = grant.grantee();
        return grantee != null
                && grantee.type() == Type.GROUP
                && grantee.uri() != null
                && (grantee.uri().endsWith("AllUsers") || grantee.uri().endsWith("AuthenticatedUsers"));
    }
}
