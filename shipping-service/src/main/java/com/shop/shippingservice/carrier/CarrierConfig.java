package com.shop.shippingservice.carrier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;

import java.util.List;

@Configuration
public class CarrierConfig {

    @Bean
    @Primary
    public CarrierAdapter primary(List<CarrierAdapter> all) {
        Assert.state(all.size() == 1,
                () -> "Expected exactly one active CarrierAdapter but found " + all.size());
        return all.get(0);
    }

    @Bean
    public CarrierFactory carrierFactory(List<CarrierAdapter> all) {
        return new CarrierFactory(all);
    }
}
