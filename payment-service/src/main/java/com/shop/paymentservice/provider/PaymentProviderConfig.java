package com.shop.paymentservice.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;

import java.util.List;

@Configuration
public class PaymentProviderConfig {

    @Bean
    @Primary
    public PaymentProvider primary(List<PaymentProvider> all) {
        Assert.state(all.size() == 1,
                () -> "Expected exactly one active PaymentProvider but found " + all.size());
        return all.get(0);
    }
}
