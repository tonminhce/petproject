package com.shop.common.storage.service;

import com.shop.common.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * H31 — {@link S3ObjectStorageService#download(String, String)} MUST release the underlying
 * AWS SDK {@link ResponseInputStream} when it returns a {@link StorageObject}.
 *
 * <p>The earlier implementation handed the live S3 stream out as
 * {@code StorageObject.content()} and relied on the caller to close it. If the caller
 * skipped the close (or read failed mid-stream), the HTTP connection backing
 * the {@code ResponseInputStream} was not returned to the pool — the classic
 * "fd leak" the report flagged. The fix wraps the S3 call in
 * try-with-resources and buffers the body before returning, so the S3 stream
 * is always closed before the storage call returns.</p>
 *
 * <p>The fake stream here is a plain {@link InputStream} wrapped in
 * {@link ResponseInputStream} whose {@code close()} flips a sentinel flag —
 * the test asserts that flag is true after {@code download()} returns,
 * proving the S3-side resource was released.</p>
 */
@ExtendWith(MockitoExtension.class)
class S3ObjectStorageServiceDownloadLeakTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Test
    void downloadReleasesUnderlyingS3StreamBeforeReturning() throws Exception {
        byte[] payload = "hello-world".getBytes();
        AtomicBoolean underlyingClosed = new AtomicBoolean(false);
        InputStream tracking = new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                underlyingClosed.set(true);
                super.close();
            }
        };
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("text/plain")
                .contentLength((long) payload.length)
                .build();
        ResponseInputStream<GetObjectResponse> s3Stream = new ResponseInputStream<>(response, tracking);

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Stream);

        StorageProperties props = new StorageProperties();
        props.setBucket("test");
        S3ObjectStorageService service = new S3ObjectStorageService(s3Client, s3Presigner, props);

        StorageObject obj = service.download("test", "k");

        // H31 pin: the S3 backing stream is closed by download() itself, before
        // the StorageObject is handed to the caller. The caller's close() on
        // .content() is now an idempotent cleanup of an in-memory copy.
        assertThat(underlyingClosed.get())
                .as("S3 stream must be closed by download() to release the HTTP connection")
                .isTrue();

        // Sanity: the returned object still carries the bytes for the caller.
        byte[] actual = obj.content().readAllBytes();
        assertThat(actual).containsExactly(payload);
        assertThat(obj.key()).isEqualTo("k");
        assertThat(obj.contentType()).isEqualTo("text/plain");
        assertThat(obj.contentLength()).isEqualTo(payload.length);
    }

    @Test
    void downloadClosesStreamEvenWhenReaderAborts() throws Exception {
        AtomicBoolean underlyingClosed = new AtomicBoolean(false);
        InputStream throwing = new InputStream() {
            @Override
            public int read() {
                throw new RuntimeException("downstream reader died");
            }

            @Override
            public int read(byte[] b, int off, int len) {
                throw new RuntimeException("downstream reader died");
            }

            @Override
            public void close() throws IOException {
                underlyingClosed.set(true);
            }
        };
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("application/octet-stream")
                .build();
        ResponseInputStream<GetObjectResponse> s3Stream = new ResponseInputStream<>(response, throwing);

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Stream);

        StorageProperties props = new StorageProperties();
        props.setBucket("test");
        S3ObjectStorageService service = new S3ObjectStorageService(s3Client, s3Presigner, props);

        StorageObject obj;
        try {
            obj = service.download("test", "k");
            // H31: even if readAllBytes() in download() blows up, the S3 stream
            // must be closed (try-with-resources) before the exception propagates.
            assertThatNoException().isThrownBy(() -> obj.content().readAllBytes());
        } catch (RuntimeException expected) {
            assertThat(underlyingClosed.get())
                    .as("S3 stream must be closed by download() even when readAllBytes() throws")
                    .isTrue();
            return;
        }
        assertThat(underlyingClosed.get())
                .as("S3 stream must be closed by download()")
                .isTrue();
    }
}