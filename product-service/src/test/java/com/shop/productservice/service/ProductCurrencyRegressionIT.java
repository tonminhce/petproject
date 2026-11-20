package com.shop.productservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.PlatformCurrency;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.entity.Product;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProductCurrencyRegressionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired ProductMapper productMapper;
    @Autowired ObjectMapper objectMapper;

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    @Test
    void create_withClientSuppliedCurrency_isIgnored_andPlatformCurrencyIsPersisted() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "title": "Currency Probe %s",
                  "slug": "currency-probe-%s",
                  "sku": "CUR-%s",
                  "priceUnit": 12345.67,
                  "quantity": 3,
                  "status": "ACTIVE",
                  "currency": "EUR"
                }
                """.formatted(suffix, suffix, suffix);

        String responseBody = mockMvc.perform(post("/api/v1/backoffice/products")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value(PlatformCurrency.DEFAULT))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(responseBody).path("data");
        UUID productId = UUID.fromString(data.path("id").asText());
        Product persisted = productRepository.findWithRelationsById(productId).orElseThrow();

        assertThat(persisted.getCurrency())
                .as("a client supplied currency must never reach the stored price")
                .isEqualTo(PlatformCurrency.DEFAULT);
        assertThat(persisted.getCurrency()).isNotEqualTo("EUR");
    }

    @Test
    void create_withoutCurrency_storesThePlatformDefault() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProductCreateRequest request = new ProductCreateRequest(
                "No Currency " + suffix,
                "no-currency-" + suffix,
                null,
                "NOC-" + suffix,
                new BigDecimal("500.00"),
                1,
                ProductStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null);

        Product entity = productMapper.toEntity(request);

        assertThat(entity.getCurrency()).isEqualTo(PlatformCurrency.DEFAULT);
    }

    @Test
    void persist_withNullCurrency_isRejectedByTheDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .title("Null Currency " + suffix)
                .slug("null-currency-" + suffix)
                .sku("NUL-" + suffix)
                .priceUnit(new BigDecimal("100.00"))
                .currency(null)
                .quantity(1)
                .status(ProductStatus.ACTIVE)
                .build();

        assertThatThrownBy(() -> productRepository.saveAndFlush(product))
                .as("a price without a currency must not be persistable")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persist_withUnsupportedCurrency_isRejectedByTheDatabaseCheckConstraint() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .title("Bad Currency " + suffix)
                .slug("bad-currency-" + suffix)
                .sku("BAD-" + suffix)
                .priceUnit(new BigDecimal("100.00"))
                .currency("EUR")
                .quantity(1)
                .status(ProductStatus.ACTIVE)
                .build();

        assertThatThrownBy(() -> productRepository.saveAndFlush(product))
                .as("a currency outside the supported set must not be persistable")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
