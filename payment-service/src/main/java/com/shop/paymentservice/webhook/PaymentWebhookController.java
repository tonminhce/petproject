package com.shop.paymentservice.webhook;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.paymentservice.service.WebhookEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.WEBHOOK_PAYMENTS)
public class PaymentWebhookController {

    private final WebhookEventService webhookEventService;
    private final String webhookSecret;

    public PaymentWebhookController(
            WebhookEventService webhookEventService,
            @Value("${shop.payment.webhook.secret}") String webhookSecret) {
        this.webhookEventService = webhookEventService;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/{provider}")
    public ApiResponse<Void> handle(
            @PathVariable String provider,
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature) {
        // C5 Task 3 — Stripe signs with its own t=/v1= scheme verified by
        // stripe-java's Webhook.constructEvent inside the service; every other
        // provider keeps the fleet HMAC verifier. Same endpoint, no additions.
        if ("stripe".equals(provider)) {
            webhookEventService.handleStripe(rawBody, stripeSignature);
            return ApiResponse.message("accepted");
        }
        if (!WebhookSignatureVerifier.verify(webhookSecret, rawBody, signature)) {
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        webhookEventService.handle(provider, rawBody);
        return ApiResponse.message("accepted");
    }
}
