package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and factory for carrier adapters supporting multi-carrier dispatch (Open-Closed Principle).
 */
public class CarrierFactory {

    private final Map<String, CarrierAdapter> carriers = new ConcurrentHashMap<>();
    private final CarrierAdapter defaultCarrier;

    public CarrierFactory(List<CarrierAdapter> carrierList) {
        CarrierAdapter first = null;
        if (carrierList != null) {
            for (CarrierAdapter c : carrierList) {
                if (c != null) {
                    String code = c.carrierCode();
                    if (code != null) {
                        carriers.put(code.toUpperCase(Locale.ROOT), c);
                    }
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
        return getCarrier(carrier.name());
    }

    public CarrierAdapter getCarrier(String carrierCode) {
        if (carrierCode == null || carrierCode.isBlank()) {
            return defaultCarrier;
        }
        CarrierAdapter adapter = carriers.get(carrierCode.trim().toUpperCase(Locale.ROOT));
        return (adapter != null) ? adapter : defaultCarrier;
    }

    public CarrierAdapter getDefaultCarrier() {
        return defaultCarrier;
    }

    public Map<String, CarrierAdapter> getAllCarriers() {
        return Collections.unmodifiableMap(carriers);
    }
}
