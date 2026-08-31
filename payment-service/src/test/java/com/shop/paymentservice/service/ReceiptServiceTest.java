package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String KEY = "receipts/" + PAYMENT_ID + ".json";
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-30T12:00:00Z");

    @Mock ObjectStorageService objectStorage;

    private ReceiptService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new ReceiptService(objectStorage, mapper);
    }

    private Payment capturedPayment() {
        Payment payment = Payment.builder()
                .id(PAYMENT_ID)
                .orderId(ORDER_ID)
                .amount(new BigDecimal("98.00"))
                .currency("USD")
                .status(PaymentStatus.CAPTURED)
                .previousStatus(PaymentStatus.PENDING)
                .provider("mock")
                .idempotencyKey("ord-1-pay-1")
                .build();
        payment.setUpdatedAt(CAPTURED_AT);
        return payment;
    }

    @Test
    void happyPath_uploadsDeterministicJson_andReturnsKey() {
        when(objectStorage.upload(eq(KEY), any(byte[].class), eq("application/json"))).thenReturn(KEY);

        String key = service.storeReceipt(capturedPayment());

        assertThat(key).isEqualTo(KEY);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(objectStorage).upload(eq(KEY), bodyCaptor.capture(), eq("application/json"));
        String json = new String(bodyCaptor.getValue(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"paymentId\":\"" + PAYMENT_ID + "\"");
        assertThat(json).contains("\"orderId\":\"" + ORDER_ID + "\"");
        assertThat(json).contains("\"amount\":98.00");
        assertThat(json).contains("\"currency\":\"USD\"");
        assertThat(json).contains("\"status\":\"CAPTURED\"");
        assertThat(json).contains("\"capturedAt\":\"2026-08-30T12:00:00Z\"");

        service.storeReceipt(capturedPayment());
        ArgumentCaptor<byte[]> secondCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(objectStorage, times(2)).upload(eq(KEY), secondCaptor.capture(), eq("application/json"));
        assertThat(secondCaptor.getAllValues().get(1)).isEqualTo(bodyCaptor.getValue());
    }

    @Test
    void storageThrows_returnsNull_noExceptionEscapes() {
        doThrow(new StorageException("s3 unreachable"))
                .when(objectStorage).upload(eq(KEY), any(byte[].class), eq("application/json"));

        String key = service.storeReceipt(capturedPayment());

        assertThat(key).isNull();
        verify(objectStorage, times(1)).upload(eq(KEY), any(byte[].class), eq("application/json"));
    }

    @Test
    void storageThrowsRuntime_returnsNull_noExceptionEscapes() {
        doThrow(new RuntimeException("connection reset"))
                .when(objectStorage).upload(eq(KEY), any(byte[].class), eq("application/json"));

        assertThatCode(() -> {
            assertThat(service.storeReceipt(capturedPayment())).isNull();
        }).doesNotThrowAnyException();
    }
}
