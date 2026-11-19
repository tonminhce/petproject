package com.shop.paymentservice.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class VNPayProviderConfiguredCondition implements Condition {

    static final String PAYMENT_URL = "shop.payment.vnpay.payment-url";
    static final String API_URL = "shop.payment.vnpay.api-url";
    static final String TMN_CODE = "shop.payment.vnpay.tmn-code";
    static final String HASH_SECRET = "shop.payment.vnpay.hash-secret";
    static final String RETURN_URL = "shop.payment.vnpay.return-url";
    static final String NOTIFY_URL = "shop.payment.vnpay.notify-url";
    static final String ENABLED = "shop.payment.vnpay.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return Boolean.parseBoolean(environment.getProperty(ENABLED))
                && MoMoProviderConfiguredCondition.isSet(environment, PAYMENT_URL)
                && MoMoProviderConfiguredCondition.isSet(environment, API_URL)
                && MoMoProviderConfiguredCondition.isSet(environment, TMN_CODE)
                && MoMoProviderConfiguredCondition.isSet(environment, HASH_SECRET)
                && MoMoProviderConfiguredCondition.isSet(environment, RETURN_URL)
                && MoMoProviderConfiguredCondition.isSet(environment, NOTIFY_URL);
    }
}
