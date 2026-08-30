package com.shop.shippingservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.shippingservice.config.ShippingWebhookProperties;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.outbox.ShippingEventPublisher;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.WebhookEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEventServiceTest {

    private static final String SECRET = "whsec_test_secret";
    private static final Carrier CARRIER = Carrier.GHN;
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Mock ShipmentRepository shipments;
    @Mock ShipmentEventRepository events;
    @Mock WebhookEventWriter writer;
    @Mock ShippingEventPublisher publisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebhookEventServiceImpl service;

    @BeforeEach
    void setUp() {
        ShippingWebhookProperties properties = new ShippingWebhookProperties();
        properties.setSecrets(Map.of(CARRIER.name(), SECRET));
        service = new WebhookEventServiceImpl(properties, shipments, events, writer, publisher, objectMapper, clock);
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String syntheticId(byte[] raw) {
        return "unparseable-" + sha256Hex(raw);
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String payloadJson(String eventId, String trackingNumber, String carrierStatus) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"carrier.event.v1\","
                + "\"trackingNumber\":\"" + trackingNumber + "\",\"carrierStatus\":\"" + carrierStatus + "\"}";
    }

    private Shipment shipment(ShipmentStatus status) {
        return Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .carrier(CARRIER)
                .trackingNumber("TRK-1")
                .status(status)
                .build();
    }

    private void happyPathStubs(String eventId) {
        when(events.existsByCarrierAndProviderEventId(CARRIER, eventId)).thenReturn(false);
    }

    @Test
    void validAdvance_updatesShipmentAndLastCarrierUpdate() {
        byte[] raw = body(payloadJson("evt-1", "TRK-1", "IN_TRANSIT"));
        happyPathStubs("evt-1");
        Shipment shipment = shipment(ShipmentStatus.PICKED_UP);
        when(shipments.findByTrackingNumber("TRK-1")).thenReturn(Optional.of(shipment));

        service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw));

        ArgumentCaptor<Shipment> shipmentCaptor = ArgumentCaptor.forClass(Shipment.class);
        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).complete(shipmentCaptor.capture(), eventCaptor.capture());
        assertThat(shipmentCaptor.getValue().getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(shipmentCaptor.getValue().getPreviousStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(shipmentCaptor.getValue().getLastCarrierUpdate()).isEqualTo(NOW);
        assertThat(shipmentCaptor.getValue().getDeliveredAt()).isNull();
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("PROCESSED");
        assertThat(eventCaptor.getValue().getShipmentId()).isEqualTo(shipment.getId());
        verifyNoInteractions(publisher);
    }

    @Test
    void delivered_setsDeliveredAtAndPublishes() {
        byte[] raw = body(payloadJson("evt-2", "TRK-1", "DELIVERED"));
        happyPathStubs("evt-2");
        Shipment shipment = shipment(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipments.findByTrackingNumber("TRK-1")).thenReturn(Optional.of(shipment));

        service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw));

        ArgumentCaptor<Shipment> shipmentCaptor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).complete(shipmentCaptor.capture(), any(ShipmentEvent.class));
        assertThat(shipmentCaptor.getValue().getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipmentCaptor.getValue().getDeliveredAt()).isEqualTo(NOW);
        verify(publisher).publishDelivered(shipmentCaptor.getValue(), false);
    }

    @Test
    void replay_isNoOp() {
        when(events.existsByCarrierAndProviderEventId(CARRIER, "evt-3")).thenReturn(true);
        byte[] raw = body(payloadJson("evt-3", "TRK-1", "IN_TRANSIT"));

        service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw));

        verify(writer, never()).insert(any(ShipmentEvent.class));
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verify(shipments, never()).findByTrackingNumber(anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void unknownTrackingNumber_marksEventFailedAndAcks() {
        byte[] raw = body(payloadJson("evt-4", "UNKNOWN", "IN_TRANSIT"));
        happyPathStubs("evt-4");
        when(shipments.findByTrackingNumber("UNKNOWN")).thenReturn(Optional.empty());

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(eventCaptor.getValue().getShipmentId()).isNull();
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void illegalTransition_marksEventFailedAndAcks() {
        byte[] raw = body(payloadJson("evt-5", "TRK-1", "IN_TRANSIT"));
        happyPathStubs("evt-5");
        when(shipments.findByTrackingNumber("TRK-1"))
                .thenReturn(Optional.of(shipment(ShipmentStatus.DELIVERED)));

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void duplicateInsertRace_acksNoOp() {
        byte[] raw = body(payloadJson("evt-6", "TRK-1", "IN_TRANSIT"));
        happyPathStubs("evt-6");
        when(writer.insert(any(ShipmentEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uk_shipment_events_carrier_event"));

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        verify(shipments, never()).findByTrackingNumber(anyString());
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void unconfiguredCarrier_throws401BeforeAnything() {
        byte[] raw = body(payloadJson("evt-7", "TRK-1", "IN_TRANSIT"));

        assertThatThrownBy(() -> service.handle("GHTK", raw, hmacHex(SECRET, raw)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getErrorCode()).isEqualTo("SHP-10004");
                });

        verifyNoInteractions(events, shipments, writer, publisher);
    }

    @Test
    void blankSecret_throws401BeforeAnything() {
        ShippingWebhookProperties blank = new ShippingWebhookProperties();
        blank.setSecrets(Map.of("GHTK", ""));
        service = new WebhookEventServiceImpl(blank, shipments, events, writer, publisher, objectMapper, clock);
        byte[] raw = body(payloadJson("evt-8", "TRK-1", "IN_TRANSIT"));

        assertThatThrownBy(() -> service.handle("GHTK", raw, hmacHex(SECRET, raw)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(events, shipments, writer, publisher);
    }

    @Test
    void unknownCarrierName_throws401BeforeAnything() {
        byte[] raw = body(payloadJson("evt-9", "TRK-1", "IN_TRANSIT"));

        assertThatThrownBy(() -> service.handle("NOT_A_CARRIER", raw, hmacHex(SECRET, raw)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(events, shipments, writer, publisher);
    }

    @Test
    void badSignature_throws401AndPersistsNothing() {
        byte[] raw = body(payloadJson("evt-10", "TRK-1", "IN_TRANSIT"));
        String signature = hmacHex("whsec_other_secret", raw);

        assertThatThrownBy(() -> service.handle(CARRIER.name(), raw, signature))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(events, shipments, writer, publisher);
    }

    @Test
    void missingSignatureHeader_throws401() {
        byte[] raw = body(payloadJson("evt-11", "TRK-1", "IN_TRANSIT"));

        assertThatThrownBy(() -> service.handle(CARRIER.name(), raw, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(events, shipments, writer, publisher);
    }

    @Test
    void unparseableBodyWithValidSignature_persistsFailedEventWithSyntheticIdAndAcks() {
        byte[] raw = body("not-a-json-payload{{{");
        when(events.existsByCarrierAndProviderEventId(CARRIER, syntheticId(raw))).thenReturn(false);

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).insert(eventCaptor.capture());
        ShipmentEvent inserted = eventCaptor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("FAILED");
        assertThat(inserted.getProviderEventId()).isEqualTo(syntheticId(raw));
        assertThat(inserted.getProviderEventId()).hasSizeLessThanOrEqualTo(128);
        assertThat(inserted.getType()).isEqualTo("UNPARSEABLE");
        assertThat(inserted.getCarrier()).isEqualTo(CARRIER);
        assertThat(inserted.getShipmentId()).isNull();
        assertThat(inserted.getPayload()).isEqualTo(new String(raw, StandardCharsets.UTF_8));
        verify(shipments, never()).findByTrackingNumber(anyString());
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void unparseableBodyRepeat_dedupesViaSyntheticIdAndAcks() {
        byte[] raw = body("not-a-json-payload{{{");
        when(events.existsByCarrierAndProviderEventId(CARRIER, syntheticId(raw))).thenReturn(true);

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        verify(writer, never()).insert(any(ShipmentEvent.class));
        verify(shipments, never()).findByTrackingNumber(anyString());
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void nullCarrierStatus_marksEventFailedAndAcks() {
        byte[] raw = body("{\"eventId\":\"evt-13\",\"eventType\":\"carrier.event.v1\",\"trackingNumber\":\"TRK-1\"}");
        happyPathStubs("evt-13");
        when(shipments.findByTrackingNumber("TRK-1"))
                .thenReturn(Optional.of(shipment(ShipmentStatus.PICKED_UP)));

        assertThatCode(() -> service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw)))
                .doesNotThrowAnyException();

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(eventCaptor.getValue().getProviderEventId()).isEqualTo("evt-13");
        verify(writer, never()).complete(any(Shipment.class), any(ShipmentEvent.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void eventRow_isPersistedFirstWithRawPayloadAndDedupeKey() {
        byte[] raw = body(payloadJson("evt-12", "UNKNOWN", "PICKED_UP"));
        happyPathStubs("evt-12");
        when(shipments.findByTrackingNumber("UNKNOWN")).thenReturn(Optional.empty());

        service.handle(CARRIER.name(), raw, hmacHex(SECRET, raw));

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(writer).insert(eventCaptor.capture());
        ShipmentEvent inserted = eventCaptor.getValue();
        assertThat(inserted.getId()).isNotNull();
        assertThat(inserted.getCarrier()).isEqualTo(CARRIER);
        assertThat(inserted.getProviderEventId()).isEqualTo("evt-12");
        assertThat(inserted.getType()).isEqualTo("carrier.event.v1");
        assertThat(inserted.getPayload()).isEqualTo(new String(raw, StandardCharsets.UTF_8));
    }
}
