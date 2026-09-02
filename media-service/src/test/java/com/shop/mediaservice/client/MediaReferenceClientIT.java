package com.shop.mediaservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-4 client wire proofs over REAL HTTP (product MediaHeadClient precedent):
 * the fail-closed contract of {@link MediaReferenceClient} — a 200 with a
 * parseable body is the ONLY path to a count; 5xx, malformed bodies, and
 * connection resets all degrade to an EMPTY result (which the checker turns
 * into fail-safe REFERENCED). The raw downstream exception never escapes the
 * client.
 */
class MediaReferenceClientIT extends AbstractMediaIntegrationTest {

    static final WireMockServer productServer = new WireMockServer(0);
    static final WireMockServer keycloakServer = new WireMockServer(0);

    @BeforeAll
    static void startStubs() {
        productServer.start();
        keycloakServer.start();
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson(
                "{\"access_token\":\"dummy-jwt\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    @AfterAll
    static void stopStubs() {
        productServer.stop();
        keycloakServer.stop();
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry r) {
        r.add("shop.product.base-url", productServer::baseUrl);
        r.add("shop.product.keycloak.token-url",
            () -> keycloakServer.url("/realms/test/protocol/openid-connect/token"));
    }

    @Autowired
    private MediaReferenceClient referenceClient;

    private static final UUID MEDIA_ID = UUID.fromString("f2000000-0000-0000-0000-000000000001");

    @BeforeEach
    void resetStubs() {
        productServer.resetAll();
    }

    private String path() {
        return "/internal/products/media-references/" + MEDIA_ID;
    }

    @Test
    @DisplayName("200 with count → OptionalLong.of(count), SERVICE token attached")
    void referenceCount_200_returnsTheCount() {
        productServer.stubFor(get(urlEqualTo(path()))
            .willReturn(okJson(
                "{\"success\":true,\"code\":\"OK\",\"data\":{\"mediaId\":\"" + MEDIA_ID
                    + "\",\"referenceCount\":3}}")));

        var count = referenceClient.referenceCount(MEDIA_ID);

        assertThat(count).hasValue(3);
        // the call carried a bearer token (client_credentials stub) — the
        // endpoint is SERVICE-gated, so an anonymous call would 401
        productServer.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(path()))
            .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.matching("Bearer .*")));
    }

    @Test
    @DisplayName("zero count → OptionalLong.of(0) — the ONLY purge-green answer")
    void referenceCount_zeroCount_returnsZero() {
        productServer.stubFor(get(urlEqualTo(path()))
            .willReturn(okJson(
                "{\"success\":true,\"code\":\"OK\",\"data\":{\"mediaId\":\"" + MEDIA_ID
                    + "\",\"referenceCount\":0}}")));

        assertThat(referenceClient.referenceCount(MEDIA_ID)).hasValue(0);
    }

    @Test
    @DisplayName("500 → empty (fail-closed), no exception leaves the client")
    void referenceCount_serverError_returnsEmpty() {
        productServer.stubFor(get(urlEqualTo(path())).willReturn(serverError()));

        assertThat(referenceClient.referenceCount(MEDIA_ID)).isEmpty();
    }

    @Test
    @DisplayName("malformed body → empty (fail-closed)")
    void referenceCount_malformedBody_returnsEmpty() {
        productServer.stubFor(get(urlEqualTo(path()))
            .willReturn(okJson("<html>not the fleet envelope</html>")));

        assertThat(referenceClient.referenceCount(MEDIA_ID)).isEmpty();
    }

    @Test
    @DisplayName("connection reset (product down) → empty (fail-closed)")
    void referenceCount_connectionReset_returnsEmpty() {
        productServer.stubFor(get(urlEqualTo(path()))
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(referenceClient.referenceCount(MEDIA_ID)).isEmpty();
    }

    @Test
    @DisplayName("404 (unknown media at product) → empty (fail-closed)")
    void referenceCount_notFound_returnsEmpty() {
        productServer.stubFor(get(urlEqualTo(path()))
            .willReturn(aResponse().withStatus(404)));

        assertThat(referenceClient.referenceCount(MEDIA_ID)).isEmpty();
    }
}
