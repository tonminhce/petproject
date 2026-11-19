package com.shop.shippingservice.carrier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class CarrierConfig {

    /**
     * Multi-carrier aware: selects the carrier matching
     * {@code shop.shipping.carrier} as the primary injection target.
     * Falls back to the first registered adapter if no match is found.
     */
    @Bean
    @Primary
    public CarrierAdapter primary(List<CarrierAdapter> all,
            @Value("${shop.shipping.carrier:manual}") String carrierName) {
        return all.stream()
                .filter(a -> a.carrier() != null
                        && a.carrier().name().equalsIgnoreCase(carrierName))
                .findFirst()
                .orElse(all.get(0));
    }

    @Bean
    public CarrierFactory carrierFactory(List<CarrierAdapter> all) {
        return new CarrierFactory(all);
    }
}
