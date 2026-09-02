package com.shop.paymentservice.webhook;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.paymentservice.service.WebhookEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    @Mock WebhookEventService webhookEventService;

    private PaymentWebhookController controller;
    private byte[] rawBody;

    @BeforeEach
    void setUp() {
        controller = new PaymentWebhookController(webhookEventService, SECRET);
        rawBody = "{\"eventId\":\"evt_123\",\"status\":\"CAPTURED\"}".getBytes(StandardCharsets.UTF_8);
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

    @Test
    void validSignature_delegatesToReceiver_andAcks() {
        ApiResponse<Void> response = controller.handle("mock", rawBody, hmacHex(SECRET, rawBody), null);

        verify(webhookEventService).handle("mock", rawBody);
        assertThat(response.success()).isTrue();
    }

    @Test
    void badSignature_throwsPay5005_beforeAnyRowIsWritten() {
        String signature = hmacHex("whsec_other_secret", rawBody);

        assertThatThrownBy(() -> controller.handle("mock", rawBody, signature, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(webhookEventService);
    }

    @Test
    void missingSignatureHeader_throwsPay5005() {
        assertThatThrownBy(() -> controller.handle("mock", rawBody, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(webhookEventService);
    }

    @Test
    void tamperedBody_throwsPay5005() {
        String signature = hmacHex(SECRET, rawBody);
        rawBody[0] = ' ';

        assertThatThrownBy(() -> controller.handle("mock", rawBody, signature, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(webhookEventService);
    }
}
