package com.shop.mediaservice.storage;

import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Type;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProvisioningIT extends AbstractMediaIntegrationTest {

    @Autowired
    private ObjectStorageService storage;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private MediaProperties mediaProperties;

    @Test
    void contextBootsWithBucketProvisioned() {
        assertThat(storage).isNotNull();
        assertThatCode(() -> s3Client.headBucket(b -> b.bucket(BUCKET)))
                .doesNotThrowAnyException();
    }

    @Test
    void bucketAclIsPrivate() {
        var acl = s3Client.getBucketAcl(b -> b.bucket(BUCKET));

        assertThat(acl.grants())
                .isNotEmpty()
                .noneMatch(ProvisioningIT::isPublicGrant);
    }

    @Test
    void mediaPropertiesAreBound() {
        assertThat(mediaProperties.bucket()).isEqualTo("media");
        assertThat(mediaProperties.presignTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(mediaProperties.maxUpload().toBytes()).isEqualTo(10 * 1024 * 1024);
        assertThat(mediaProperties.purgeGrace()).isEqualTo(Duration.ofDays(30));
    }

    private static boolean isPublicGrant(Grant grant) {
        Grantee grantee = grant.grantee();
        return grantee != null
                && grantee.type() == Type.GROUP
                && grantee.uri() != null
                && (grantee.uri().endsWith("AllUsers") || grantee.uri().endsWith("AuthenticatedUsers"));
    }
}
