package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Option C write-time gate end-to-end over REAL HTTP (media epic spec D5):
 * backoffice create with a mediaId HEAD-checks media-service through the live
 * {@code MediaHeadClient} (WireMock-backed media + client-credentials token
 * stub). 200 → accepted and persisted; 404 → rejected MED-12004 (404, common
 * i18n message); media down/5xx → write fails MED-12006 (503). A product must
 * never be persisted against an unverified media reference.
 */
@AutoConfigureTestRestTemplate
class MediaHeadValidationIT extends AbstractIntegrationTest {

    static final WireMockServer mediaServer = new WireMockServer(0);
    static final WireMockServer keycloakServer = new WireMockServer(0);

    static {
        mediaServer.start();
        keycloakServer.start();
        // ServiceTokenProvider's client_credentials token endpoint (cached after first fetch).
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson(
                "{\"access_token\":\"dummy-jwt\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    @AfterAll
    static void stopStubs() {
        mediaServer.stop();
        keycloakServer.stop();
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry r) {
        r.add("shop.media.base-url", mediaServer::baseUrl);
        r.add("shop.media.keycloak.token-url",
            () -> keycloakServer.url("/realms/test/protocol/openid-connect/token"));
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "it-admin-token";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    /** Token → Jwt stub: realm_access roles drive {@code hasRole('ADMIN')} (MediaControllerIT precedent). */
    @TestConfiguration(proxyBeanMethods = false)
    static class ItJwtConfig {

        @Bean
        @Primary
        JwtDecoder itJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("99999999-9999-9999-9999-999999999999")
                .claim("preferred_username", "it-admin")
                .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                .build();
        }
    }

    @BeforeEach
    void resetStubsAndState() {
        mediaServer.resetAll();
        productRepository.deleteAllInBatch();
        outboxRepository.deleteAllInBatch();
    }

    private ResponseEntity<String> create(UUID mediaId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        String body = """
            {"title":"iPhone 15","slug":"iphone-15-%s","sku":"IP15-%s",
             "priceUnit":999.00,"quantity":10,"status":"ACTIVE",
             "imageUrl":"http://legacy.example/ip15.png","mediaId":"%s"}
            """.formatted(UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID().toString().substring(0, 8), mediaId);
        return rest.postForEntity(ApiPaths.BACKOFFICE_PRODUCTS, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("HEAD 200 → create accepted: media_id persisted, derived imageUrl in the response")
    void headOk_mediaAcceptedAndPersisted() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withStatus(200)));

        ResponseEntity<String> resp = create(mediaId);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = readBody(resp.getBody());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data").get("mediaId").asText()).isEqualTo(mediaId.toString());
        assertThat(body.get("data").get("imageUrl").asText())
            .isEqualTo(ApiPaths.MEDIAS + "/" + mediaId);
        var saved = productRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getMediaId()).isEqualTo(mediaId);
        mediaServer.verify(com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId)));
    }

    @Test
    @DisplayName("HEAD 404 → 404 MED-12004 with the common i18n message; nothing persisted")
    void headNotFound_rejectedWithMed12004() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> resp = create(mediaId);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = readBody(resp.getBody());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo("MED-12004");
        assertThat(body.get("message").asText()).isEqualTo("Media not found");
        assertThat(productRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        mediaServer.verify(com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId)));
    }

    @Test
    @DisplayName("media down (connection reset) → 503 MED-12006; the write fails")
    void mediaDown_failsTheWriteWithMed12006() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<String> resp = create(mediaId);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        JsonNode body = readBody(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("MED-12006");
        assertThat(productRepository.count()).isZero();
    }

    @Test
    @DisplayName("media 500 → 503 MED-12006; the write fails")
    void mediaServerError_failsTheWriteWithMed12006() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(serverError()));

        ResponseEntity<String> resp = create(mediaId);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        JsonNode body = readBody(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("MED-12006");
        assertThat(productRepository.count()).isZero();
    }

    @Test
    @DisplayName("null mediaId → no media call, legacy imageUrl path intact")
    void nullMediaId_skipsHeadCheck() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        String body = """
            {"title":"Legacy Product","slug":"legacy-%s","sku":"LEG-%s",
             "priceUnit":5.00,"quantity":1,"status":"ACTIVE",
             "imageUrl":"http://legacy.example/legacy.png"}
            """.formatted(UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID().toString().substring(0, 8));
        ResponseEntity<String> resp = rest.postForEntity(ApiPaths.BACKOFFICE_PRODUCTS,
            new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode json = readBody(resp.getBody());
        assertThat(json.get("data").get("mediaId").isNull()).isTrue();
        assertThat(json.get("data").get("imageUrl").asText()).isEqualTo("http://legacy.example/legacy.png");
        assertThat(mediaServer.getServeEvents().getRequests())
            .as("no media call for null mediaId").isEmpty();
    }

    // --- H-2: explicit clearMediaId on PUT ---

    @Test
    @DisplayName("PUT clearMediaId=true → media_id cleared end-to-end, legacy fallback, no extra HEAD call")
    void putClearMediaId_clearsReferenceEndToEnd() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withStatus(200)));
        ResponseEntity<String> created = create(mediaId);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        UUID productId = UUID.fromString(readBody(created.getBody()).get("data").get("id").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        ResponseEntity<String> resp = rest.exchange(ApiPaths.BACKOFFICE_PRODUCTS + "/" + productId,
            HttpMethod.PUT, new HttpEntity<>("{\"clearMediaId\": true}", headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode json = readBody(resp.getBody());
        assertThat(json.get("data").get("mediaId").isNull()).as("clear must persist a null media_id").isTrue();
        assertThat(json.get("data").get("imageUrl").asText())
            .as("derived image falls back to the legacy imageUrl (D5)")
            .isEqualTo("http://legacy.example/ip15.png");
        Product fresh = productRepository.findById(productId).orElseThrow();
        assertThat(fresh.getMediaId()).isNull();
        // exactly ONE media call overall — the create-time gate; a clear never re-verifies
        assertThat(mediaServer.getServeEvents().getRequests()).hasSize(1);
        List<OutboxEvent> updates = outboxRepository.findAll().stream()
            .filter(r -> "ProductUpdated".equals(r.getEventType())).toList();
        assertThat(updates).as("cleared product is re-published for the search refresh").hasSize(1);
    }

    @Test
    @DisplayName("PUT clearMediaId=true together with mediaId → 400 ERR-0422-V, nothing persisted")
    void putConflictingClearAndMediaId_rejected400() {
        UUID mediaId = UUID.randomUUID();
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withStatus(200)));
        ResponseEntity<String> created = create(mediaId);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        UUID productId = UUID.fromString(readBody(created.getBody()).get("data").get("id").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        ResponseEntity<String> resp = rest.exchange(ApiPaths.BACKOFFICE_PRODUCTS + "/" + productId,
            HttpMethod.PUT, new HttpEntity<>(
                "{\"mediaId\": \"" + mediaId + "\", \"clearMediaId\": true}", headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode json = readBody(resp.getBody());
        assertThat(json.get("code").asText()).isEqualTo("ERR-0422-V");
        // T2 review carry-over: pin the INTERPOLATED constraint message (the
        // raw template "{product.media.clear.conflict}" would not contain this
        // fragment — both bundles render "mediaClearConsistent: clearMediaId=true …").
        assertThat(json.get("errors").get(0).asText())
            .contains("mediaClearConsistent")
            .contains("clearMediaId=true");
        assertThat(productRepository.findById(productId).orElseThrow().getMediaId())
            .as("conflicting body must not touch the stored reference").isEqualTo(mediaId);
    }

    // --- helpers ---

    private JsonNode readBody(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
