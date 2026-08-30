package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.entity.TaxClass;
import com.shop.taxservice.entity.TaxRate;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxCalculationServiceImplTest {

    @Mock
    private TaxClassRepository taxClassRepository;

    @Mock
    private TaxRateRepository taxRateRepository;

    @InjectMocks
    private TaxCalculationServiceImpl service;

    private final UUID classId = UUID.randomUUID();

    private TaxClass taxClass(String name, String defaultRatePct) {
        return TaxClass.builder().id(classId).name(name).defaultRatePct(new BigDecimal(defaultRatePct)).build();
    }

    private TaxRate taxRate(String postalCode, String ratePct) {
        return TaxRate.builder().taxClassId(classId).country("DE").postalCode(postalCode).ratePct(new BigDecimal(ratePct)).build();
    }

    private TaxCalculateRequest request(String postalCode) {
        return new TaxCalculateRequest(classId, "DE", postalCode, new BigDecimal("100.00"));
    }

    @Test
    void tier1HitReturnsPostalSpecificRate() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard", "19.00")));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", "10115")).thenReturn(Optional.of(taxRate("10115", "7.00")));

        TaxCalculateResponse response = service.calculate(request("10115"));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("7.00"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("7.00"));
        verify(taxRateRepository, never()).findByTaxClassIdAndCountryAndPostalCodeIsNull(any(), anyString());
    }

    @Test
    void tier1MissFallsBackToCountryWideRate() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard", "19.00")));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", "10115")).thenReturn(Optional.empty());
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCodeIsNull(classId, "DE")).thenReturn(Optional.of(taxRate(null, "9.50")));

        TaxCalculateResponse response = service.calculate(request("10115"));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("9.50"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("9.50"));
    }

    @Test
    void bothTiersMissFallsBackToClassDefault() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard", "19.00")));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", "10115")).thenReturn(Optional.empty());
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCodeIsNull(classId, "DE")).thenReturn(Optional.empty());

        TaxCalculateResponse response = service.calculate(request("10115"));

        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("19.00"));
        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("19.00"));
    }

    @Test
    void unknownClassThrowsTax8001() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));

        verify(taxRateRepository, never()).findByTaxClassIdAndCountryAndPostalCode(any(), anyString(), anyString());
    }

    @Test
    void noRateAnywhereThrowsTax8002() {
        TaxClass nullDefault = TaxClass.builder().id(classId).name("Zero").defaultRatePct(null).build();
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(nullDefault));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", "10115")).thenReturn(Optional.empty());
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCodeIsNull(classId, "DE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8002"));
    }

    @Test
    void blankPostalCodeIsNormalizedToNullBeforeTier1() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard", "19.00")));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", null)).thenReturn(Optional.of(taxRate(null, "9.50")));

        service.calculate(request("   "));

        ArgumentCaptor<String> postalCaptor = ArgumentCaptor.forClass(String.class);
        verify(taxRateRepository).findByTaxClassIdAndCountryAndPostalCode(any(), anyString(), postalCaptor.capture());
        assertThat(postalCaptor.getValue()).isNull();
    }

    @Test
    void nullPostalCodeSkipsLookupWithPostal() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard", "19.00")));
        when(taxRateRepository.findByTaxClassIdAndCountryAndPostalCode(classId, "DE", null)).thenReturn(Optional.of(taxRate(null, "9.50")));

        service.calculate(request(null));

        verify(taxRateRepository).findByTaxClassIdAndCountryAndPostalCode(classId, "DE", null);
    }
}
