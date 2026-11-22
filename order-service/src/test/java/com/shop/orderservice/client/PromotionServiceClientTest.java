package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PromotionReserveRequest;
import com.shop.orderservice.exception.PromotionApplicationRejectedException;
import com.shop.orderservice.security.ServiceTokenProvider;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class PromotionServiceClientTest {

    private static final String RESERVE_URL = "http://promotion.test/api/v1/promotions/SUMMER10/reserve";

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private PromotionServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://promotion.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ShopServicesProperties props = new ShopServicesProperties(null, null, null,
            new ShopServicesProperties.Service("http://promotion.test", 3000, true), null, null);
        client = new PromotionServiceClient(props, builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("svc-token");
    }

    private PromotionReserveRequest request() {
        return new PromotionReserveRequest("SUMMER10", new BigDecimal("100.00"), "VND",
            UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void aRejectedCouponSurfacesAsAPromotionApplicationRejectionWithThePromotionErrorCode() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"PRM-4001\",\"message\":\"coupon expired\"}"));

        assertThatThrownBy(() -> client.reserve(request()))
            .isInstanceOfSatisfying(PromotionApplicationRejectedException.class,
                ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("ORD-4008");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        server.verify();
    }

    @Test
    void aPromotionServerErrorStillFailsClosedAsServiceUnavailable() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.reserve(request()))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));

        server.verify();
    }

    @Test
    void anAcceptedCouponStillReturnsTheReservation() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andRespond(withSuccess(
                "{\"success\":true,\"code\":\"OK\",\"data\":{\"reservationId\":\"0f39a2d8-6a1e-4b1e-9a7c-2b3c4d5e6f70\",\"discountAmount\":10.00,\"finalAmount\":90.00,\"currency\":\"VND\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.reserve(request()).discountAmount()).isEqualByComparingTo(new BigDecimal("10.00"));

        server.verify();
    }
}
