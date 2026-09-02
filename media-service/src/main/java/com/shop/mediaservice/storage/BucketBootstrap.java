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
 * missing (idempotent) and then asserts the ACL carries no public grant. The
 * two failure modes are split (final review F-4), stated precisely:
 *
 * <ul>
 *   <li><strong>FATAL — ACL violation:</strong> the bucket exists but the ACL
 *   assertion itself fails ({@link IllegalStateException} from
 *   {@link #assertPrivateAcl}): the runner rethrows and STARTUP CRASHES. A
 *   misconfigured public bucket must never serve private media, not even
 *   degraded.</li>
 *   <li><strong>DEGRADE — storage outage:</strong> connectivity/SDK errors
 *   from bucket discovery, creation or the ACL read are logged and swallowed,
 *   so the service boots without its bucket. PRECISELY: an
 *   {@code ApplicationRunner} runs ONCE — a degraded boot stays degraded
 *   until the service RESTARTS with the object store reachable; the bootstrap
 *   does not retry, and no later code re-creates the bucket (uploads/presigns
 *   will fail MED-12006 until then). Degrade is thus a restart-gated
 *   recovery, not a self-healing one.</li>
 * </ul>
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
        } catch (IllegalStateException e) {
            // the private-ACL assertion failed — refuse to boot on a misconfigured (public) bucket
            throw e;
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
