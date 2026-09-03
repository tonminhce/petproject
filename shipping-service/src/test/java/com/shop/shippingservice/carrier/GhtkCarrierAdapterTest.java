package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GhtkCarrierAdapterTest {

    private GhtkCarrierAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GhtkCarrierAdapter("TOKEN");
    }

    @Test
    void carrier_returnsGhtk() {
        assertThat(adapter.carrier()).isEqualTo(Carrier.GHTK);
    }

    @Test
    void createShipment_generatesValidDraft() {
        UUID orderId = UUID.randomUUID();
        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(orderId);

        assertThat(draft.initialStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(draft.trackingNumber()).startsWith("GHTK.");
    }
}
