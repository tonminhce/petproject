package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptService {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String KEY_TEMPLATE = "receipts/%s.json";

    private final ObjectStorageService objectStorage;
    private final ObjectMapper objectMapper;

    public String storeReceipt(Payment payment) {
        String key = KEY_TEMPLATE.formatted(payment.getId());
        try {
            Receipt receipt = new Receipt(
                    payment.getId(),
                    payment.getOrderId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    payment.getStatus(),
                    payment.getUpdatedAt());
            objectStorage.upload(key, objectMapper.writeValueAsBytes(receipt), CONTENT_TYPE_JSON);
            return key;
        } catch (Exception e) {
            log.warn("Receipt storage failed (paymentId={}, key={})", payment.getId(), key, e);
            return null;
        }
    }

    private record Receipt(
            UUID paymentId,
            UUID orderId,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            Instant capturedAt
    ) {
    }
}
