package com.shop.ratingservice.eligibility;

import com.shop.ratingservice.config.RatingClientProperties;
import com.shop.ratingservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class EligibilityClientTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String VERIFY_URL = "http://order.test/api/v1/orders/verify-purchase?userId=" + USER_ID + "&productId=" + PRODUCT_ID;

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private EligibilityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://order.test");
        server = MockRestServiceServer.bindTo(builder).build();
        RatingClientProperties props = new RatingClientProperties(
            new RatingClientProperties.OrderService("http://order.test", 3000), null);
        client = new EligibilityClient(props, builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("test-token");
    }

    private static String itemJson(UUID productId, String title, int quantity) {
        return """
            {"productId":"%s","productTitle":"%s","quantity":%d,"unitPrice":110.00,"lineTotal":%s}
            """.formatted(productId, title, quantity, "220.00");
    }

    private static String pageJson(String... entries) {
        return """
            {"success":true,"code":"OK","data":{"content":[%s],"page":0,"size":20,"totalElements":%d,"totalPages":1,"first":true,"last":true}}
            """.formatted(String.join(",", entries), entries.length);
    }

    @Test
    void deliveredItemYieldsTrue() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(pageJson(itemJson(PRODUCT_ID, "Classic Tee", 2)), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isTrue();
    }

    @Test
    void emptyPageYieldsFalse() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess(pageJson(), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isFalse();
    }

    @Test
    void connectionRefusedFailsClosed() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withException(new IOException("Connection refused")));
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withServerError());

        assertThat(failingClosed()).isFalse();
        assertThat(failingClosed()).isFalse();
    }

    @Test
    void malformedBodyFailsClosed() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));

        assertThat(failingClosed()).isFalse();
    }

    @Test
    void sendsBearerServiceToken() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess(pageJson(itemJson(PRODUCT_ID, "Classic Tee", 1)), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isTrue();
        server.verify();
    }

    @Test
    void nullDataFailsClosed() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":null}", MediaType.APPLICATION_JSON));

        assertThat(failingClosed()).isFalse();
    }

    /** Fail-closed contract: ANY client failure must surface as false, never a raw throw. */
    private boolean failingClosed() {
        try {
            return client.isEligible(USER_ID, PRODUCT_ID);
        } catch (RuntimeException ex) {
            throw new AssertionError("Client must fail closed (false), not throw", ex);
        }
    }
}
