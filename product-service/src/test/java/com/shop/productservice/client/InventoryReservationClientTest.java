package com.shop.productservice.client;

import com.shop.productservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-060: the delete-time stock guard client is fail-closed.
 *
 * <p>An answer the client cannot read is NOT an answer of "no live
 * reservation" — it is UNKNOWN, and the caller must refuse the delete. A
 * delete must never happen on doubt.</p>
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationClientTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final String URL = "http://inventory.test/internal/inventory/reservations/" + PRODUCT_ID;

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private InventoryReservationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InventoryReservationClient(builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("test-token");
    }

    @Test
    @DisplayName("200 with a parseable body → the live reservation count")
    void okYieldsTheCount() {
        stubToken();
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess("""
                    {"success":true,"code":"OK","data":{"productId":"%s","activeReservationCount":3}}
                    """.formatted(PRODUCT_ID), MediaType.APPLICATION_JSON));

        assertThat(client.activeReservationCount(PRODUCT_ID)).hasValue(3);
        server.verify();
    }

    @Test
    @DisplayName("a server error → UNKNOWN (the delete must not go through)")
    void serverErrorYieldsUnknown() {
        stubToken();
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(client.activeReservationCount(PRODUCT_ID)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a transport failure → UNKNOWN (the delete must not go through)")
    void transportFailureYieldsUnknown() {
        stubToken();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("inventory down")));

        assertThat(client.activeReservationCount(PRODUCT_ID)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a malformed body → UNKNOWN (the delete must not go through)")
    void malformedBodyYieldsUnknown() {
        stubToken();
        server.expect(requestTo(URL))
            .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.activeReservationCount(PRODUCT_ID)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a 404 means the endpoint does not exist → UNKNOWN, not 'no reservation'")
    void notFoundYieldsUnknown() {
        stubToken();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.activeReservationCount(PRODUCT_ID)).isEmpty();
        server.verify();
    }
}
