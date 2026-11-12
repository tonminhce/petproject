package com.shop.mediaservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoded-memory budget of the upload path (F-062).
 *
 * <p>The budget of this suite is 1 MB: an 800 x 600 image decodes to
 * 800 x 600 x 4 = 1.9 MB, so it is rejected - BEFORE it is decoded, so the
 * service never allocates the pixels. With the old pixel guard (8192 x 8192
 * = 268 MB) the very same upload was accepted and the heap spike was the
 * outage.</p>
 */
@AutoConfigureTestRestTemplate
class MediaUploadMemoryBudgetIT extends AbstractMediaIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "it-admin-token";

    @DynamicPropertySource
    static void budgetProps(DynamicPropertyRegistry registry) {
        registry.add("media.max-decoded-bytes", () -> "1MB");
    }

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

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("an upload above the decoded-memory budget → 413 MED-12002")
    void anUploadAboveTheBudgetIsRejected() throws Exception {
        // a unique size per run: media-service dedupes by sha256, and the
        // shared Postgres already holds the deterministic 800x600 image from
        // the other IT suites - a dedup hit would answer 200 without ever
        // decoding the image
        int width = 800 + Math.abs(UUID.randomUUID().hashCode() % 50);
        ResponseEntity<String> response = upload(TestImages.jpeg(width, 600));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(JSON.readTree(response.getBody()).get("code").asText()).isEqualTo("MED-12002");
    }

    @Test
    @DisplayName("an upload within the decoded-memory budget → 201")
    void anUploadWithinTheBudgetIsAccepted() throws Exception {
        ResponseEntity<String> response = upload(TestImages.jpeg(120, 90));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(JSON.readTree(response.getBody()).get("data").get("id").asText()).isNotBlank();
    }

    private ResponseEntity<String> upload(byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(ADMIN_TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "image.jpg";
            }
        });
        return rest.postForEntity(ApiPaths.BACKOFFICE_MEDIAS, new HttpEntity<>(body, headers), String.class);
    }
}
