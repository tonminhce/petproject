package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.TaxCalculateRequest;
import com.shop.orderservice.exception.TaxCalculationRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaxServiceClientTest {

    private static final String CALCULATE_URL = "http://tax.test/api/v1/backoffice/tax-rates/calculate";

    private MockRestServiceServer server;
    private TaxServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tax.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ShopServicesProperties props = new ShopServicesProperties(null, null,
            new ShopServicesProperties.Service("http://tax.test", 3000, true), null, null, null);
        client = new TaxServiceClient(props, builder.build());
    }

    private TaxCalculateRequest request() {
        return new TaxCalculateRequest(null, null, null, new BigDecimal("100.00"), "VND");
    }

    @Test
    void aTaxRefusalSurfacesAsATaxCalculationRejectionWithTheTaxErrorCode() {
        server.expect(requestTo(CALCULATE_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"TAX-4001\",\"message\":\"no tax class\"}"));

        assertThatThrownBy(() -> client.calculate(request()))
            .isInstanceOfSatisfying(TaxCalculationRejectedException.class,
                ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("ORD-4009");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        server.verify();
    }

    @Test
    void aTaxServerErrorStillFailsClosedAsServiceUnavailable() {
        server.expect(requestTo(CALCULATE_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.calculate(request()))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ERR-0503"));

        server.verify();
    }

    @Test
    void aTaxSuccessStillReturnsTheCalculatedTax() {
        server.expect(requestTo(CALCULATE_URL))
            .andRespond(withSuccess(
                "{\"success\":true,\"code\":\"OK\",\"data\":{\"taxAmount\":10.00,\"appliedRate\":0.1,\"currency\":\"VND\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.calculate(request()).taxAmount()).isEqualByComparingTo(new BigDecimal("10.00"));

        server.verify();
    }
}
