package com.shop.shippingservice.carrier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.constant.Carrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-067 (P1) — the carrier adapters must call the real carrier and must fail
 * loudly when they cannot.
 *
 * <p>The previous GHN/GHTK adapters derived a tracking number from the order id
 * and never contacted the carrier, so the platform produced shipments that no
 * carrier knew about - and the reconciliation scheduler then auto-delivered
 * them, marking the order DELIVERED for a parcel that was never shipped.</p>
 */
class CarrierShipmentClientTest {

    private static final String ENDPOINT = "https://carrier.test/api/shipments";

    private MockRestServiceServer server;
    private CarrierShipmentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CarrierShipmentClient(builder, new ObjectMapper());
    }

    @Test
    void trackingNumberIsTakenFromTheCarrierResponse() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"data\":{\"tracking_number\":\"GHN-REAL-1\"}}", MediaType.APPLICATION_JSON));

        String tracking = client.createShipment("GHN", ENDPOINT, "token", "shop-1",
                UUID.randomUUID(), "data.tracking_number");

        assertThat(tracking).isEqualTo("GHN-REAL-1");
        server.verify();
    }

    @Test
    void aResponseWithoutATrackingNumberIsRejectedRatherThanFabricated() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createShipment("GHN", ENDPOINT, "token", "shop-1",
                UUID.randomUUID(), "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }

    @Test
    void aCarrierErrorIsSurfacedAsCarrierNotConfigured() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withServerError());

        assertThatThrownBy(() -> client.createShipment("GHN", ENDPOINT, "token", "shop-1",
                UUID.randomUUID(), "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }

    @Test
    void ghnAdapterRefusesToStartWithoutAnEndpoint() {
        CarrierShipmentClient unused = client;
        assertThatThrownBy(() -> new GhnCarrierAdapter(unused, "token", "shop", "", "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }

    @Test
    void ghtkAdapterRefusesToStartWithoutAnEndpoint() {
        CarrierShipmentClient unused = client;
        assertThatThrownBy(() -> new GhtkCarrierAdapter(unused, "token", "", "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }

    @Test
    void ghnAdapterUsesTheCarrierIssuedTrackingNumber() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"data\":{\"tracking_number\":\"GHN-CARRIER-9\"}}", MediaType.APPLICATION_JSON));
        GhnCarrierAdapter adapter = new GhnCarrierAdapter(client, "token", "shop", ENDPOINT, "data.tracking_number");

        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(UUID.randomUUID());

        assertThat(draft.trackingNumber()).isEqualTo("GHN-CARRIER-9");
        assertThat(draft.initialStatus()).isEqualTo(
                com.shop.shippingservice.constant.ShipmentStatus.CREATED);
        assertThat(adapter.carrier()).isEqualTo(Carrier.GHN);
        server.verify();
    }
}
