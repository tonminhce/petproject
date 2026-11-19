package com.shop.ratingservice.eligibility;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.keycloak.exception.KeycloakServiceTokenException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static String eligibilityJson(boolean eligible, long count) {
        return """
            {"success":true,"code":"OK","data":{"eligible":%s,"deliveredItemCount":%d}}
            """.formatted(eligible, count);
    }

    @Test
    void deliveredItemYieldsTrue() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(eligibilityJson(true, 2), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isTrue();
    }

    @Test
    void noDeliveredItemYieldsFalse() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess(eligibilityJson(false, 0), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isFalse();
    }

    @Test
    void connectionRefusedIsReportedAsDependencyFailure() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withException(new IOException("Connection refused")));
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withServerError());

        assertDependencyFailure();
        assertDependencyFailure();
    }

    @Test
    void malformedBodyIsReportedAsDependencyFailure() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));

        assertDependencyFailure();
    }

    @Test
    void sendsBearerServiceToken() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess(eligibilityJson(true, 1), MediaType.APPLICATION_JSON));

        assertThat(client.isEligible(USER_ID, PRODUCT_ID)).isTrue();
        server.verify();
    }

    @Test
    void nullDataIsReportedAsDependencyFailure() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":null}", MediaType.APPLICATION_JSON));

        assertDependencyFailure();
    }

    @Test
    void tokenProviderFailureIsReportedAsDependencyFailure() {
        lenient().when(serviceTokenProvider.getToken())
            .thenThrow(new KeycloakServiceTokenException("Keycloak token endpoint is failing"));

        assertDependencyFailure();
        server.verify();
    }

    @Test
    void dependencyFailureCarriesTheServiceUnavailableCode() {
        stubToken();
        server.expect(requestTo(VERIFY_URL))
            .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.isEligible(USER_ID, PRODUCT_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException businessException = (BusinessException) ex;
                assertThat(businessException.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(businessException.getErrorCode()).isEqualTo("ERR-0503");
            });
    }

    private void assertDependencyFailure() {
        assertThatThrownBy(() -> client.isEligible(USER_ID, PRODUCT_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException businessException = (BusinessException) ex;
                assertThat(businessException.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(businessException.getErrorCode()).isEqualTo("ERR-0503");
            });
    }
}
