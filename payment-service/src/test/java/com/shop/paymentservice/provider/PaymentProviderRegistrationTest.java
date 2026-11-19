package com.shop.paymentservice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.paymentservice.config.PaymentMoMoProperties;
import com.shop.paymentservice.config.PaymentVNPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class, ClientConfig.class);

    @Configuration
    @EnableConfigurationProperties({PaymentMoMoProperties.class, PaymentVNPayProperties.class})
    static class PropsConfig {
    }

    @Configuration
    static class ClientConfig {

        @Bean("momoRestClient")
        RestClient momoRestClient() {
            return RestClient.builder().build();
        }

        @Bean("vnpayRestClient")
        RestClient vnpayRestClient() {
            return RestClient.builder().build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void momoProviderIsNotRegisteredWithoutAFullConfiguration() {
        contextRunner.withUserConfiguration(MoMoProvider.class)
                .withPropertyValues(
                        "shop.payment.momo.enabled=true",
                        "shop.payment.momo.base-url=https://test-payment.momo.vn",
                        "shop.payment.momo.partner-code=PARTNER",
                        "shop.payment.momo.access-key=ACCESS",
                        "shop.payment.momo.secret-key=SECRET")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MoMoProvider.class);
                });
    }

    @Test
    void momoProviderIsNotRegisteredWithoutAnExplicitEnable() {
        contextRunner.withUserConfiguration(MoMoProvider.class)
                .withPropertyValues(
                        "shop.payment.momo.base-url=https://test-payment.momo.vn",
                        "shop.payment.momo.partner-code=PARTNER",
                        "shop.payment.momo.access-key=ACCESS",
                        "shop.payment.momo.secret-key=SECRET",
                        "shop.payment.momo.return-url=https://shop.test/momo/return",
                        "shop.payment.momo.notify-url=https://shop.test/momo/ipn")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MoMoProvider.class);
                });
    }

    @Test
    void momoProviderIsRegisteredWithAFullConfiguration() {
        contextRunner.withUserConfiguration(MoMoProvider.class)
                .withPropertyValues(
                        "shop.payment.momo.enabled=true",
                        "shop.payment.momo.base-url=https://test-payment.momo.vn",
                        "shop.payment.momo.partner-code=PARTNER",
                        "shop.payment.momo.access-key=ACCESS",
                        "shop.payment.momo.secret-key=SECRET",
                        "shop.payment.momo.return-url=https://shop.test/momo/return",
                        "shop.payment.momo.notify-url=https://shop.test/momo/ipn")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(MoMoProvider.class);
                });
    }

    @Test
    void vnpayProviderIsNotRegisteredWithoutTheCallbackUrls() {
        contextRunner.withUserConfiguration(VNPayProvider.class)
                .withPropertyValues(
                        "shop.payment.vnpay.enabled=true",
                        "shop.payment.vnpay.payment-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                        "shop.payment.vnpay.api-url=https://sandbox.vnpayment.vn",
                        "shop.payment.vnpay.tmn-code=TMN",
                        "shop.payment.vnpay.hash-secret=SECRET")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(VNPayProvider.class);
                });
    }

    @Test
    void vnpayProviderIsRegisteredWithAFullConfiguration() {
        contextRunner.withUserConfiguration(VNPayProvider.class)
                .withPropertyValues(
                        "shop.payment.vnpay.enabled=true",
                        "shop.payment.vnpay.payment-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                        "shop.payment.vnpay.api-url=https://sandbox.vnpayment.vn",
                        "shop.payment.vnpay.tmn-code=TMN",
                        "shop.payment.vnpay.hash-secret=SECRET",
                        "shop.payment.vnpay.return-url=https://shop.test/vnpay/return",
                        "shop.payment.vnpay.notify-url=https://shop.test/vnpay/ipn")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(VNPayProvider.class);
                });
    }
}
