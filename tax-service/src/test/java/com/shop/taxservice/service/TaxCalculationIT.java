package com.shop.taxservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.entity.TaxClass;
import com.shop.taxservice.entity.TaxRate;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import com.shop.taxservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxCalculationIT extends AbstractIntegrationTest {

    @Autowired
    private TaxClassRepository taxClassRepository;

    @Autowired
    private TaxRateRepository taxRateRepository;

    @Autowired
    private TaxCalculationService taxCalculationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TaxClass standard;

    @BeforeEach
    void seedClassWithThreeRates() {
        standard = taxClassRepository.save(TaxClass.builder()
            .name("IT Calc Standard " + UUID.randomUUID())
            .defaultRatePct(new BigDecimal("19.00"))
            .build());
        taxRateRepository.save(TaxRate.builder()
            .taxClassId(standard.getId())
            .country("DE")
            .postalCode("10115")
            .ratePct(new BigDecimal("7.00"))
            .build());
        taxRateRepository.save(TaxRate.builder()
            .taxClassId(standard.getId())
            .country("DE")
            .postalCode(null)
            .ratePct(new BigDecimal("9.50"))
            .build());
        taxRateRepository.save(TaxRate.builder()
            .taxClassId(standard.getId())
            .country("CH")
            .postalCode(null)
            .ratePct(new BigDecimal("50.00"))
            .build());
    }

    @Test
    void tier1PostalSpecificRateWins() {
        TaxCalculateResponse response = taxCalculationService.calculate(
            new TaxCalculateRequest(standard.getId(), "DE", "10115", new BigDecimal("100.00")));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("7.00"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void tier2CountryWideRateWhenPostalMisses() {
        TaxCalculateResponse response = taxCalculationService.calculate(
            new TaxCalculateRequest(standard.getId(), "DE", "80331", new BigDecimal("100.00")));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("9.50"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("9.50"));
    }

    @Test
    void tier3ClassDefaultWhenCountryMisses() {
        TaxCalculateResponse response = taxCalculationService.calculate(
            new TaxCalculateRequest(standard.getId(), "FR", null, new BigDecimal("100.00")));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("19.00"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("19.00"));
    }

    @Test
    void roundingIsHalfUpAgainstRealNumerics() {
        TaxCalculateResponse response = taxCalculationService.calculate(
            new TaxCalculateRequest(standard.getId(), "CH", null, new BigDecimal("0.05")));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("50.00"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("0.03"));
    }

    @Test
    void noRateAnywhereThrowsTax8002() {
        jdbcTemplate.execute("alter table tax_classes alter column default_rate_pct drop not null");
        UUID noDefaultClassId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                "insert into tax_classes (id, name, default_rate_pct, created_at, updated_at, version, deleted) "
                    + "values (?, ?, null, now(), now(), 0, false)",
                noDefaultClassId, "IT Calc NoDefault " + noDefaultClassId);

            assertThatThrownBy(() -> taxCalculationService.calculate(
                new TaxCalculateRequest(noDefaultClassId, "US", "99999", new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                    assertThat(ex.getErrorCode()).isEqualTo("TAX-8002"));
        } finally {
            jdbcTemplate.update("delete from tax_classes where id = ?", noDefaultClassId);
            jdbcTemplate.execute("alter table tax_classes alter column default_rate_pct set not null");
        }
    }

    @Test
    void unknownClassThrowsTax8001() {
        assertThatThrownBy(() -> taxCalculationService.calculate(
            new TaxCalculateRequest(UUID.randomUUID(), "DE", "10115", new BigDecimal("100.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));
    }
}
