package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry and factory for carrier adapters supporting multi-carrier dispatch.
 */
public class CarrierFactory {

    private final Map<Carrier, CarrierAdapter> carriers = new EnumMap<>(Carrier.class);
    private final CarrierAdapter defaultCarrier;

    public CarrierFactory(List<CarrierAdapter> carrierList) {
        CarrierAdapter first = null;
        if (carrierList != null) {
            for (CarrierAdapter c : carrierList) {
                if (c != null && c.carrier() != null) {
                    carriers.put(c.carrier(), c);
                    if (first == null) {
                        first = c;
                    }
                }
            }
        }
        this.defaultCarrier = first;
    }

    public CarrierAdapter getCarrier(Carrier carrier) {
        if (carrier == null) {
            return defaultCarrier;
        }
        CarrierAdapter adapter = carriers.get(carrier);
        return (adapter != null) ? adapter : defaultCarrier;
    }

    public CarrierAdapter getDefaultCarrier() {
        return defaultCarrier;
    }

    public Map<Carrier, CarrierAdapter> getAllCarriers() {
        return Collections.unmodifiableMap(carriers);
    }
}
