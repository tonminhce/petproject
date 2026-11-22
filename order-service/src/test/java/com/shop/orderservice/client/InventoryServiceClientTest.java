package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.exception.StockReservationFailedException;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class InventoryServiceClientTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String RESERVE_URL =
            "http://inventory.test/api/v1/inventory/" + PRODUCT_ID + "/reserve";

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private InventoryServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InventoryServiceClient(builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("svc-token");
    }

    @Test
    void aSoldOutRefusalSurfacesAsAStockReservationFailureAndNotAsADenial() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"INV-3002\",\"message\":\"out of stock\"}"));

        assertThatThrownBy(() -> client.reserve(PRODUCT_ID, new ReserveRequest(2, UUID.randomUUID())))
            .isInstanceOfSatisfying(StockReservationFailedException.class,
                ex -> assertThat(ex.getProductId()).isEqualTo(PRODUCT_ID));

        server.verify();
    }

    @Test
    void anUnknownProductRefusalSurfacesAsAStockReservationFailure() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.reserve(PRODUCT_ID, new ReserveRequest(2, UUID.randomUUID())))
            .isInstanceOf(StockReservationFailedException.class);

        server.verify();
    }

    @Test
    void anInventoryServerErrorFailsClosedAsServiceUnavailable() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.reserve(PRODUCT_ID, new ReserveRequest(2, UUID.randomUUID())))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));

        server.verify();
    }

    @Test
    void anInventoryAuthRefusalFailsClosedAsServiceUnavailableAndIsNotABusinessRejection() {
        stubToken();
        server.expect(requestTo(RESERVE_URL))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.reserve(PRODUCT_ID, new ReserveRequest(2, UUID.randomUUID())))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));

        server.verify();
    }
}
