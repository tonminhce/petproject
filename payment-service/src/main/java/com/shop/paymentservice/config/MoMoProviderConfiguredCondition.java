package com.shop.paymentservice.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class MoMoProviderConfiguredCondition implements Condition {

    static final String BASE_URL = "shop.payment.momo.base-url";
    static final String PARTNER_CODE = "shop.payment.momo.partner-code";
    static final String ACCESS_KEY = "shop.payment.momo.access-key";
    static final String SECRET_KEY = "shop.payment.momo.secret-key";
    static final String RETURN_URL = "shop.payment.momo.return-url";
    static final String NOTIFY_URL = "shop.payment.momo.notify-url";
    static final String ENABLED = "shop.payment.momo.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return Boolean.parseBoolean(environment.getProperty(ENABLED))
                && isSet(environment, BASE_URL)
                && isSet(environment, PARTNER_CODE)
                && isSet(environment, ACCESS_KEY)
                && isSet(environment, SECRET_KEY)
                && isSet(environment, RETURN_URL)
                && isSet(environment, NOTIFY_URL);
    }

    static boolean isSet(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value != null && !value.isBlank();
    }
}
