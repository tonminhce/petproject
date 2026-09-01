package com.shop.productservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.config.MediaClientProperties;
import com.shop.productservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Option C write-time existence-check client (media epic spec D5): 200 → true,
 * 404 → false, ANY other failure → MED-12006 503 (the write must fail, never
 * silently accept an unverified reference).
 */
@ExtendWith(MockitoExtension.class)
class MediaHeadClientTest {

    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final String HEAD_URL = "http://media.test/api/v1/medias/" + MEDIA_ID;

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private MediaHeadClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://media.test");
        server = MockRestServiceServer.bindTo(builder).build();
        MediaClientProperties props = new MediaClientProperties(
            "http://media.test", 3000,
            new MediaClientProperties.Keycloak("http://kc.test/token", "product-service", "changeme"));
        client = new MediaHeadClient(builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("test-token");
    }

    @Test
    @DisplayName("HEAD 200 → true (media exists)")
    void headOkYieldsTrue() {
        stubToken();
        server.expect(requestTo(HEAD_URL))
            .andExpect(method(HttpMethod.HEAD))
            .andRespond(withSuccess());

        assertThat(client.exists(MEDIA_ID)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("HEAD 404 → false (unknown media — caller rejects MED-12004)")
    void headNotFoundYieldsFalse() {
        stubToken();
        server.expect(requestTo(HEAD_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.exists(MEDIA_ID)).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("sends the SERVICE bearer token per call")
    void sendsBearerServiceToken() {
        stubToken();
        server.expect(requestTo(HEAD_URL))
            .andExpect(method(HttpMethod.HEAD))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess());

        assertThat(client.exists(MEDIA_ID)).isTrue();
    }

    @Test
    @DisplayName("media down (connection refused) → MED-12006 503 — write fails")
    void connectionRefusedFailsTheWrite() {
        stubToken();
        server.expect(requestTo(HEAD_URL))
            .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.exists(MEDIA_ID))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("MED-12006");
                assertThat(ex.getStatus().value()).isEqualTo(503);
            });
    }

    @Test
    @DisplayName("media 5xx → MED-12006 503 — write fails")
    void serverErrorFailsTheWrite() {
        stubToken();
        server.expect(requestTo(HEAD_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.exists(MEDIA_ID))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("MED-12006"));
    }
}
