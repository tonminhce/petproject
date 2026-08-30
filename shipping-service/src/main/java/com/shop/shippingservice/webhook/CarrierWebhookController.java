package com.shop.shippingservice.webhook;

import com.shop.common.core.constants.ApiPaths;
import com.shop.shippingservice.service.WebhookEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.WEBHOOK_SHIPPING)
@RequiredArgsConstructor
public class CarrierWebhookController {

    private final WebhookEventService service;

    @PostMapping(value = "/{carrier}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @PathVariable String carrier,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        service.handle(carrier, rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
