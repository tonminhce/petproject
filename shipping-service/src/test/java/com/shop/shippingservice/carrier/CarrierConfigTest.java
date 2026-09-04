package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CarrierConfig.class, ManualCarrierAdapter.class, NoopCarrierAdapter.class);

    @Test
    void manualIsActiveByDefaultAndResolvesAsPrimary() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasBean("manualCarrierAdapter");
            // Noop requires explicit property, so not loaded here
            assertThat(ctx).doesNotHaveBean("noopCarrierAdapter");
            assertThat(ctx.getBean(CarrierAdapter.class)).isSameAs(ctx.getBean("manualCarrierAdapter"));
            assertThat(ctx.getBean(CarrierAdapter.class).carrier()).isEqualTo(Carrier.MANUAL);
        });
    }

    @Test
    void noopSelectedWhenConfigured() {
        contextRunner.withPropertyValues("shop.shipping.carrier=noop")
                .run(ctx -> {
                    assertThat(ctx).hasBean("noopCarrierAdapter");
                    assertThat(ctx).doesNotHaveBean("manualCarrierAdapter");
                    assertThat(ctx.getBean(CarrierAdapter.class)).isSameAs(ctx.getBean("noopCarrierAdapter"));
                    assertThat(ctx.getBean(CarrierAdapter.class).carrier()).isEqualTo(Carrier.NOOP);
                });
    }

    @Test
    void multipleAdaptersCoexistAndFactoryRoutesCorrectly() {
        new ApplicationContextRunner()
                .withUserConfiguration(CarrierConfig.class, ManualCarrierAdapter.class, ExtraAdapterConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // Primary resolves to manual (default property)
                    assertThat(ctx.getBean(CarrierAdapter.class).carrier()).isEqualTo(Carrier.MANUAL);
                    // Factory contains both adapters
                    CarrierFactory factory = ctx.getBean(CarrierFactory.class);
                    assertThat(factory.getCarrier(Carrier.MANUAL).carrier()).isEqualTo(Carrier.MANUAL);
                    assertThat(factory.getCarrier(Carrier.GHN).carrier()).isEqualTo(Carrier.GHN);
                });
    }

    @Configuration
    static class ExtraAdapterConfig {

        @Bean
        CarrierAdapter extraCarrierAdapter() {
            return new CarrierAdapter() {
                @Override
                public Carrier carrier() {
                    return Carrier.GHN;
                }

                @Override
                public CarrierAdapter.ShipmentDraft createShipment(UUID orderId) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
