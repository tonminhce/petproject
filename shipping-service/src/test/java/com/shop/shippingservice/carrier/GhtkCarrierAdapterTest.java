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
 * F-067 (P1) — the GHTK adapter must call the carrier.
 *
 * <p>The previous test asserted that the generated tracking number starts with
 * "GHTK." - the locally derived, fabricated value. The adapter never contacted
 * GHTK. See {@link GhnCarrierAdapterTest} for the full consequence.</p>
 */
class GhtkCarrierAdapterTest {

    private static final String ENDPOINT = "https://ghtk.test/api/shipments";

    private MockRestServiceServer server;
    private GhtkCarrierAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GhtkCarrierAdapter(new CarrierShipmentClient(builder, new ObjectMapper()),
                "TOKEN", ENDPOINT, "data.tracking_number");
    }

    @Test
    void carrier_returnsGhtk() {
        assertThat(adapter.carrier()).isEqualTo(Carrier.GHTK);
    }

    @Test
    void createShipment_usesTheCarrierIssuedTrackingNumber() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"data\":{\"tracking_number\":\"GHTK-CARRIER-7\"}}",
                        MediaType.APPLICATION_JSON));
        UUID orderId = UUID.randomUUID();

        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(orderId);

        assertThat(draft.initialStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(draft.trackingNumber()).isEqualTo("GHTK-CARRIER-7");
        server.verify();
    }

    @Test
    void adapterRefusesToStartWithoutAConfiguredEndpoint() {
        CarrierShipmentClient client = new CarrierShipmentClient(RestClient.builder(), new ObjectMapper());
        assertThatThrownBy(() -> new GhtkCarrierAdapter(client, "TOKEN", "", "data.tracking_number"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CARRIER_NOT_CONFIGURED.getCode()));
    }
}
