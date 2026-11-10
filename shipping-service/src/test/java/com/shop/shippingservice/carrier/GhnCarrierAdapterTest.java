package com.shop.shippingservice.carrier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-067 (P1) — the GHN adapter must call the carrier.
 *
 * <p>The previous test asserted that the generated tracking number starts with
 * "GHN" - which was the locally derived, fabricated value. The adapter never
 * contacted GHN, so no parcel ever existed while the platform reported a
 * shipment, and the reconciliation scheduler auto-delivered the phantom parcel
 * after seven days, marking the order DELIVERED.</p>
 */
class GhnCarrierAdapterTest {

    private static final String ENDPOINT = "https://ghn.test/api/shipments";

    private MockRestServiceServer server;
    private GhnCarrierAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GhnCarrierAdapter(new CarrierShipmentClient(builder, new ObjectMapper()),
                "TOKEN", "123", ENDPOINT, "data.tracking_number");
    }

    @Test
    void carrier_returnsGhn() {
        assertThat(adapter.carrier()).isEqualTo(Carrier.GHN);
    }

    @Test
    void createShipment_usesTheCarrierIssuedTrackingNumber() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"data\":{\"tracking_number\":\"GHN-CARRIER-42\"}}",
                        MediaType.APPLICATION_JSON));
        UUID orderId = UUID.randomUUID();

        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(orderId);

        assertThat(draft.initialStatus()).isEqualTo(ShipmentStatus.CREATED);
        // The tracking number now comes from the carrier, not from the order id.
        assertThat(draft.trackingNumber()).isEqualTo("GHN-CARRIER-42");
        server.verify();
    }

    @Test
    void adapterRefusesToStartWithoutAConfiguredEndpoint() {
        CarrierShipmentClient client = new CarrierShipmentClient(RestClient.builder(), new ObjectMapper());
        assertThatThrownBy(() -> new GhnCarrierAdapter(client, "TOKEN", "123", "", "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }
}
