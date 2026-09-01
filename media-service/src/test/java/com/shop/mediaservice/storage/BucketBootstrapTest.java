package com.shop.mediaservice.storage;

import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.Grantee;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Type;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-4 split of the bootstrap failure modes: an ACL violation on the private
 * assertion is a FATAL misconfiguration (the runner rethrows → startup
 * crashes — a public bucket must never serve private media, not even
 * degraded), while storage unavailability (bucket discovery/creation/ACL
 * read connectivity errors) only logs and degrades — the service boots and
 * recovers once the object store is reachable.
 */
@ExtendWith(MockitoExtension.class)
class BucketBootstrapTest {

    @Mock
    private ObjectStorageService storage;

    @Mock
    private S3Client s3Client;

    private BucketBootstrap bootstrap;

    private static final String BUCKET = "media";

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties(
                BUCKET, Duration.ofDays(7), DataSize.ofMegabytes(10), Duration.ofDays(30), 1200, 320);
        bootstrap = new BucketBootstrap(storage, s3Client, properties);
    }

    @Test
    @DisplayName("ACL violation (bucket exists but has public grants) → FATAL: IllegalStateException propagates, startup crashes")
    void aclViolation_isFatal() {
        when(s3Client.getBucketAcl(any(Consumer.class))).thenReturn(aclWithPublicGrant());

        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public ACL grants");
    }

    @Test
    @DisplayName("storage outage at boot (bucket check fails) → degrade: logged, no throw, service boots")
    void storageOutage_degrades() {
        doThrow(new StorageException("S3 down", null)).when(storage).ensureBucketExists(BUCKET);

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("ACL read outage at boot → degrade too: connectivity is tolerance, not misconfig")
    void aclReadOutage_degrades() {
        when(s3Client.getBucketAcl(any(Consumer.class))).thenThrow(S3Exception.builder().message("timeout").build());

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("happy path: private bucket → no throw, no fatal")
    void privateBucket_bootsClean() {
        when(s3Client.getBucketAcl(any(Consumer.class))).thenReturn(aclPrivate());

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    private static GetBucketAclResponse aclWithPublicGrant() {
        return GetBucketAclResponse.builder()
                .grants(
                        Grant.builder()
                                .grantee(Grantee.builder().type(Type.CANONICAL_USER).displayName("owner").build())
                                .permission(Permission.FULL_CONTROL)
                                .build(),
                        Grant.builder()
                                .grantee(Grantee.builder().type(Type.GROUP)
                                        .uri("http://acs.amazonaws.com/groups/global/AllUsers").build())
                                .permission(Permission.READ)
                                .build())
                .build();
    }

    private static GetBucketAclResponse aclPrivate() {
        return GetBucketAclResponse.builder()
                .grants(Grant.builder()
                        .grantee(Grantee.builder().type(Type.CANONICAL_USER).displayName("owner").build())
                        .permission(Permission.FULL_CONTROL)
                        .build())
                .build();
    }
}
