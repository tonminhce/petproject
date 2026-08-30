package com.shop.shippingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "shop.shipping.webhook")
public class ShippingWebhookProperties {

    private Map<String, String> secrets = new HashMap<>();

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }
}
