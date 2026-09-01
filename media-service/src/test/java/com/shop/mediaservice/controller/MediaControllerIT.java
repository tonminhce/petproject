package com.shop.mediaservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-HTTP wire proofs over the full stack (real MinIO + Postgres +
 * Liquibase): multipart upload through the actual servlet multipart parser
 * (closes the T2 multipart-location watch-item), 201→200 dedup, the 302
 * presigned redirect whose Location is a VALID presigned URL (followed
 * manually — redirect-following disabled — and fetched from MinIO), HEAD
 * existence, and the delete guard family 404/409 against the real DB.
 * 401/403 matrices are slice-covered in the controller tests.
 */
@AutoConfigureTestRestTemplate
class MediaControllerIT extends AbstractMediaIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "it-admin-token";
    private static final String USER_TOKEN = "it-user-token";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaProperties properties;

    private int port;

    @BeforeEach
    void wipeAndReadPort() {
        // native DELETE — the entity's @SQLRestriction hides soft-deleted rows
        // from bulk JPQL too, so deleteAllInBatch would leave them behind
        jdbcTemplate.update("DELETE FROM media_variants");
        jdbcTemplate.update("DELETE FROM medias");
        while (true) {
            List<String> keys = new java.util.ArrayList<>();
            s3Client.listObjectsV2Paginator(b -> b.bucket(BUCKET))
                    .contents()
                    .forEach(summary -> keys.add(summary.key()));
            if (keys.isEmpty()) {
                break;
            }
            keys.forEach(key -> s3Client.deleteObject(b -> b.bucket(BUCKET).key(key)));
        }
        port = rest.getRestTemplate().getUriTemplateHandler().expand("/").getPort();
    }

    /** Token → Jwt stub: realm_access roles drive {@code hasRole('ADMIN')}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ItJwtConfig {

        @Bean
        @Primary
        JwtDecoder itJwtDecoder() {
            return token -> {
                List<String> roles = token.startsWith("it-admin") ? List.of("ADMIN") : List.of("USER");
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject("99999999-9999-9999-9999-999999999999")
                        .claim("preferred_username", token.startsWith("it-admin") ? "it-admin" : "it-customer")
                        .claim("realm_access", Map.of("roles", roles))
                        .build();
            };
        }
    }

    // --- upload via REAL multipart HTTP ---

    @Test
    @DisplayName("multipart POST → 201 created; same bytes again → 200 duplicate:true (real HTTP)")
    void uploadViaRealHttpMultipart_201Then200Duplicate() throws Exception {
        byte[] source = TestImages.jpeg(800, 600);

        ResponseEntity<String> first = upload(source, ADMIN_TOKEN);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        JsonNode body = JSON.readTree(first.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("duplicate").asBoolean()).isFalse();
        assertThat(body.get("data").get("canonicalPath").asText())
                .isEqualTo(ApiPaths.MEDIAS + "/" + body.get("data").get("id").asText());
        String mediaId = body.get("data").get("id").asText();

        ResponseEntity<String> second = upload(source, ADMIN_TOKEN);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        JsonNode dup = JSON.readTree(second.getBody());
        assertThat(dup.get("data").get("duplicate").asBoolean()).isTrue();
        assertThat(dup.get("data").get("id").asText()).isEqualTo(mediaId);
    }

    @Test
    @DisplayName("real-HTTP multipart with a non-admin token → 403")
    void uploadViaRealHttp_customerRole_403() throws Exception {
        assertThat(upload(TestImages.jpeg(64, 64), USER_TOKEN).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("real-HTTP multipart without a token → 401")
    void uploadViaRealHttp_anonymous_401() throws Exception {
        assertThat(upload(TestImages.jpeg(64, 64), null).getStatusCode().value()).isEqualTo(401);
    }

    // --- presigned GET: 302 Location that actually WORKS against MinIO ---

    @Test
    @DisplayName("GET default (display+auto) → 302 presigned Location; fetching it yields WebP bytes from MinIO")
    void get_answers302WithWorkingPresignedLocation() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(1600, 900));

        Http get = http("GET", ApiPaths.MEDIAS + "/" + mediaId, ADMIN_TOKEN);
        assertThat(get.status).isEqualTo(302);
        String location = get.location;
        assertThat(location).isNotBlank();
        assertThat(location).contains(mediaId + "/display.webp");
        assertThat(location).contains("X-Amz-Signature");

        // the presigned URL is valid — MinIO serves the object without any auth header
        HttpURLConnection fetch = (HttpURLConnection) URI.create(location).toURL().openConnection();
        fetch.setRequestMethod("GET");
        assertThat(fetch.getResponseCode()).isEqualTo(200);
        assertThat(fetch.getContentType()).isEqualTo("image/webp");
        try (InputStream in = fetch.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            assertThat(bytes).isNotEmpty();
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isLessThanOrEqualTo(properties.displayWidth());
        }
    }

    @Test
    @DisplayName("GET variant=thumb&format=webp → 302 Location pointing at the thumb WebP object")
    void get_presignMatrix_thumbWebp() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(800, 600));

        Http get = http("GET", ApiPaths.MEDIAS + "/" + mediaId + "?variant=thumb&format=webp", ADMIN_TOKEN);
        assertThat(get.status).isEqualTo(302);
        assertThat(get.location).contains(mediaId + "/thumb.webp");
    }

    @Test
    @DisplayName("GET unknown variant → 404 MED-12004; unknown media → 404 MED-12004")
    void get_unknownVariantOrMedia_404Med12004() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(800, 600));

        Http unknownVariant = http("GET", ApiPaths.MEDIAS + "/" + mediaId + "?variant=hero", ADMIN_TOKEN);
        assertThat(unknownVariant.status).isEqualTo(404);
        assertThat(JSON.readTree(unknownVariant.body).get("code").asText()).isEqualTo("MED-12004");

        Http unknownMedia = http("GET",
                ApiPaths.MEDIAS + "/00000000-0000-0000-0000-00000000dead", ADMIN_TOKEN);
        assertThat(unknownMedia.status).isEqualTo(404);
        assertThat(JSON.readTree(unknownMedia.body).get("code").asText()).isEqualTo("MED-12004");
    }

    // --- HEAD: existence without presign ---

    @Test
    @DisplayName("HEAD existing media → 200 (no redirect, no presign); unknown → 404")
    void head_answers200or404() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(800, 600));

        assertThat(http("HEAD", ApiPaths.MEDIAS + "/" + mediaId, ADMIN_TOKEN).status).isEqualTo(200);
        assertThat(http("HEAD", ApiPaths.MEDIAS + "/00000000-0000-0000-0000-00000000dead", ADMIN_TOKEN).status)
                .isEqualTo(404);
    }

    // --- soft delete + repeat-delete guard against the real DB ---

    @Test
    @DisplayName("DELETE → 200; repeat DELETE → 409 MED-12005; deleted media reads 404 everywhere")
    void deleteThenRepeatDelete_409Med12005() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(800, 600));

        Http first = http("DELETE", ApiPaths.BACKOFFICE_MEDIAS + "/" + mediaId, ADMIN_TOKEN);
        assertThat(first.status).isEqualTo(200);

        Http repeat = http("DELETE", ApiPaths.BACKOFFICE_MEDIAS + "/" + mediaId, ADMIN_TOKEN);
        assertThat(repeat.status).isEqualTo(409);
        assertThat(JSON.readTree(repeat.body).get("code").asText()).isEqualTo("MED-12005");

        // deleted media is invisible to reads (404) but a FRESH delete of an
        // unknown id is 404 too — the guard distinguishes via MED codes only
        assertThat(http("GET", ApiPaths.MEDIAS + "/" + mediaId, ADMIN_TOKEN).status).isEqualTo(404);
        assertThat(http("HEAD", ApiPaths.MEDIAS + "/" + mediaId, ADMIN_TOKEN).status).isEqualTo(404);

        Http unknown = http("DELETE",
                ApiPaths.BACKOFFICE_MEDIAS + "/00000000-0000-0000-0000-00000000dead", ADMIN_TOKEN);
        assertThat(unknown.status).isEqualTo(404);
        assertThat(JSON.readTree(unknown.body).get("code").asText()).isEqualTo("MED-12004");
    }

    @Test
    @DisplayName("DELETE with a non-admin token → 403")
    void delete_customerRole_403() throws Exception {
        String mediaId = uploadAndId(TestImages.jpeg(800, 600));

        assertThat(http("DELETE", ApiPaths.BACKOFFICE_MEDIAS + "/" + mediaId, USER_TOKEN).status)
                .isEqualTo(403);
    }

    // --- helpers ---

    private ResponseEntity<String> upload(byte[] bytes, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        return rest.postForEntity(ApiPaths.BACKOFFICE_MEDIAS, new HttpEntity<>(body, headers), String.class);
    }

    private String uploadAndId(byte[] bytes) throws Exception {
        ResponseEntity<String> response = upload(bytes, ADMIN_TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JSON.readTree(response.getBody()).get("data").get("id").asText();
    }

    private record Http(int status, String body, String location) {
    }

    private Http http(String method, String pathAndQuery, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create("http://localhost:" + port + pathAndQuery).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        String body = "HEAD".equals(method) ? "" : readBody(conn, status);
        conn.disconnect();
        return new Http(status, body, location);
    }

    private String readBody(HttpURLConnection conn, int status) throws Exception {
        InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
