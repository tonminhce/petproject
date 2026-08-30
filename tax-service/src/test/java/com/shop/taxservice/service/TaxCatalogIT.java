package com.shop.taxservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.request.TaxClassRequest;
import com.shop.taxservice.dto.request.TaxRateRequest;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxCatalogIT extends AbstractIntegrationTest {

    @Autowired
    private TaxClassService taxClassService;

    @Autowired
    private TaxRateService taxRateService;

    @Autowired
    private TaxCalculationService taxCalculationService;

    @Test
    void duplicateRateKeyRejectedWithTax8003() {
        TaxClassResponse taxClass = taxClassService.create(
            new TaxClassRequest("IT Catalog Dup", new BigDecimal("19.00")));
        TaxRateRequest request = new TaxRateRequest(taxClass.id(), "DE", null, new BigDecimal("19.00"));

        taxRateService.create(request);

        assertThatThrownBy(() -> taxRateService.create(request))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));
    }

    @Test
    void recreateAfterSoftDeleteSucceeds() {
        TaxClassResponse taxClass = taxClassService.create(
            new TaxClassRequest("IT Catalog Recreate", new BigDecimal("19.00")));
        TaxRateResponse original = taxRateService.create(
            new TaxRateRequest(taxClass.id(), "DE", "20095", new BigDecimal("7.00")));

        taxRateService.delete(original.id());

        TaxRateResponse recreated = taxRateService.create(
            new TaxRateRequest(taxClass.id(), "DE", "20095", new BigDecimal("8.00")));

        assertThat(recreated.id()).isNotEqualTo(original.id());
        List<TaxRateResponse> liveRates = taxRateService.list(taxClass.id());
        assertThat(liveRates).hasSize(1);
        assertThat(liveRates.get(0).id()).isEqualTo(recreated.id());
        assertThat(liveRates.get(0).ratePct()).isEqualTo(new BigDecimal("8.00"));
    }

    @Test
    void softDeletedClassIsInvisibleToCalculate() {
        TaxClassResponse taxClass = taxClassService.create(
            new TaxClassRequest("IT Catalog Deleted", new BigDecimal("19.00")));

        taxClassService.delete(taxClass.id());

        assertThatThrownBy(() -> taxCalculationService.calculate(
            new TaxCalculateRequest(taxClass.id(), "DE", null, new BigDecimal("100.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));
    }

    @Test
    void classWithRatesCannotBeDeleted() {
        TaxClassResponse taxClass = taxClassService.create(
            new TaxClassRequest("IT Catalog Guard", new BigDecimal("19.00")));
        taxRateService.create(new TaxRateRequest(taxClass.id(), "DE", null, new BigDecimal("19.00")));

        assertThatThrownBy(() -> taxClassService.delete(taxClass.id()))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8004"));

        assertThat(taxClassService.get(taxClass.id()).id()).isEqualTo(taxClass.id());
    }
}
