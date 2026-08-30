package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.taxservice.dto.request.TaxRateRequest;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.entity.TaxRate;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxRateServiceImplTest {

    @Mock
    private TaxRateRepository taxRateRepository;

    @Mock
    private TaxClassRepository taxClassRepository;

    @Mock
    private AuditorAware<String> auditorAware;

    @InjectMocks
    private TaxRateServiceImpl service;

    private final UUID rateId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();

    private TaxRate taxRate(String postalCode) {
        return TaxRate.builder().id(rateId).taxClassId(classId).country("DE")
            .postalCode(postalCode).ratePct(new BigDecimal("19.00")).build();
    }

    private TaxRateRequest request(String postalCode) {
        return new TaxRateRequest(classId, "DE", postalCode, new BigDecimal("19.00"));
    }

    @Test
    void createSavesAndReturnsResponse() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", "10115", null)).thenReturn(0L);
        when(taxRateRepository.save(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));

        TaxRateResponse response = service.create(request("10115"));

        assertThat(response.taxClassId()).isEqualTo(classId);
        assertThat(response.country()).isEqualTo("DE");
        assertThat(response.postalCode()).isEqualTo("10115");
        assertThat(response.ratePct()).isEqualTo(new BigDecimal("19.00"));
    }

    @Test
    void createDuplicateThrowsTax8003() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", "10115", null)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));

        verify(taxRateRepository, never()).save(any());
    }

    @Test
    void createBlankPostalMatchesExistingNullPostalDup() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", null, null)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("   ")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));

        verify(taxRateRepository, never()).save(any());
    }

    @Test
    void createBlankPostalIsNormalizedToNullOnSave() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", null, null)).thenReturn(0L);
        when(taxRateRepository.save(any(TaxRate.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(request("   "));

        ArgumentCaptor<TaxRate> captor = ArgumentCaptor.forClass(TaxRate.class);
        verify(taxRateRepository).save(captor.capture());
        assertThat(captor.getValue().getPostalCode()).isNull();
    }

    @Test
    void createUnknownClassThrowsTax8001BeforeDupCheck() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));

        verify(taxRateRepository, never()).countDuplicate(any(), any(), any(), any());
    }

    @Test
    void updateAppliesFieldsWithExcludeIdGuard() {
        TaxRate existing = taxRate("10115");
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.of(existing));
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", "20095", rateId)).thenReturn(0L);
        when(taxRateRepository.save(existing)).thenReturn(existing);

        TaxRateResponse response = service.update(rateId, new TaxRateRequest(classId, "DE", "20095", new BigDecimal("7.00")));

        assertThat(response.postalCode()).isEqualTo("20095");
        assertThat(response.ratePct()).isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void updateToDuplicateTupleThrowsTax8003() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.of(taxRate("10115")));
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(new com.shop.taxservice.entity.TaxClass()));
        when(taxRateRepository.countDuplicate(classId, "DE", "10115", rateId)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(rateId, request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));

        verify(taxRateRepository, never()).save(any());
    }

    @Test
    void updateUnknownClassThrowsTax8001() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.of(taxRate("10115")));
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(rateId, request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));

        verify(taxRateRepository, never()).countDuplicate(any(), any(), any(), any());
    }

    @Test
    void getReturnsResponse() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.of(taxRate("10115")));

        TaxRateResponse response = service.get(rateId);

        assertThat(response.id()).isEqualTo(rateId);
        assertThat(response.country()).isEqualTo("DE");
    }

    @Test
    void getUnknownRateThrowsTax8005() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(rateId))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8005");
                assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void updateUnknownRateThrowsTax8005() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(rateId, request("10115")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8005"));

        verify(taxClassRepository, never()).findById(any());
    }

    @Test
    void deleteUnknownRateThrowsTax8005() {
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(rateId))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8005"));

        verify(auditorAware, never()).getCurrentAuditor();
    }

    @Test
    void listByClassReturnsRates() {
        when(taxRateRepository.findAllByTaxClassId(classId)).thenReturn(List.of(taxRate("10115")));

        List<TaxRateResponse> responses = service.list(classId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).postalCode()).isEqualTo("10115");
    }

    @Test
    void deleteIsSoftAndUnguarded() {
        TaxRate existing = taxRate("10115");
        when(taxRateRepository.findById(rateId)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("admin-1"));

        service.delete(rateId);

        ArgumentCaptor<TaxRate> captor = ArgumentCaptor.forClass(TaxRate.class);
        verify(taxRateRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
        assertThat(captor.getValue().getDeletedBy()).isEqualTo("admin-1");
        verify(taxRateRepository, never()).countByClassId(any());
    }
}
