package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierAdapterDraftsTest {

    @Test
    void manualDraftHasNoTrackingAndStartsAtCreated() {
        ManualCarrierAdapter adapter = new ManualCarrierAdapter();

        assertThat(adapter.carrier()).isEqualTo(Carrier.MANUAL);
        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(UUID.randomUUID());
        assertThat(draft.trackingNumber()).isNull();
        assertThat(draft.initialStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void manualDraftIsValueEqualForShape() {
        ManualCarrierAdapter adapter = new ManualCarrierAdapter();

        assertThat(adapter.createShipment(UUID.randomUUID()))
                .isEqualTo(new CarrierAdapter.ShipmentDraft(null, ShipmentStatus.CREATED));
    }

    @Test
    void noopDraftTracksShipmentIdAndStartsAtPickedUp() {
        NoopCarrierAdapter adapter = new NoopCarrierAdapter();
        UUID shipmentId = UUID.randomUUID();

        assertThat(adapter.carrier()).isEqualTo(Carrier.NOOP);
        CarrierAdapter.ShipmentDraft draft = adapter.createShipment(shipmentId);
        assertThat(draft.trackingNumber()).isEqualTo("NOOP-" + shipmentId);
        assertThat(draft.initialStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void noopTrackingSuffixParsesBackToShipmentIdAndDiffersPerShipment() {
        NoopCarrierAdapter adapter = new NoopCarrierAdapter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        String tracking = adapter.createShipment(first).trackingNumber();

        assertThat(tracking).startsWith("NOOP-");
        assertThat(UUID.fromString(tracking.substring("NOOP-".length()))).isEqualTo(first);
        assertThat(adapter.createShipment(second).trackingNumber())
                .isNotEqualTo(adapter.createShipment(first).trackingNumber());
    }
}
