package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.data.SoftDeletable;
import com.shop.taxservice.dto.request.TaxClassRequest;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.entity.TaxClass;
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
class TaxClassServiceImplTest {

    @Mock
    private TaxClassRepository taxClassRepository;

    @Mock
    private TaxRateRepository taxRateRepository;

    @Mock
    private AuditorAware<String> auditorAware;

    @InjectMocks
    private TaxClassServiceImpl service;

    private final UUID classId = UUID.randomUUID();

    private TaxClass taxClass(String name) {
        return TaxClass.builder().id(classId).name(name).defaultRatePct(new BigDecimal("19.00")).build();
    }

    @Test
    void createSavesAndReturnsResponse() {
        when(taxClassRepository.findByNameIgnoreCase("Standard")).thenReturn(Optional.empty());
        when(taxClassRepository.save(any(TaxClass.class))).thenAnswer(inv -> inv.getArgument(0));

        TaxClassResponse response = service.create(new TaxClassRequest("Standard", new BigDecimal("19.00")));

        assertThat(response.name()).isEqualTo("Standard");
        assertThat(response.defaultRatePct()).isEqualTo(new BigDecimal("19.00"));
        ArgumentCaptor<TaxClass> captor = ArgumentCaptor.forClass(TaxClass.class);
        verify(taxClassRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Standard");
    }

    @Test
    void createDuplicateNameThrowsTax8003WithNameParam() {
        when(taxClassRepository.findByNameIgnoreCase("Standard")).thenReturn(Optional.of(taxClass("Standard")));

        assertThatThrownBy(() -> service.create(new TaxClassRequest("Standard", new BigDecimal("19.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));

        verify(taxClassRepository, never()).save(any());
    }

    @Test
    void createDuplicateNameIgnoreCaseThrowsTax8003() {
        when(taxClassRepository.findByNameIgnoreCase("STANDARD")).thenReturn(Optional.of(taxClass("standard")));

        assertThatThrownBy(() -> service.create(new TaxClassRequest("STANDARD", new BigDecimal("19.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));
    }

    @Test
    void updateAppliesFieldsAndReturnsResponse() {
        TaxClass existing = taxClass("Old");
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(existing));
        when(taxClassRepository.existsByNameIgnoreCaseAndIdNot("New", classId)).thenReturn(false);
        when(taxClassRepository.save(existing)).thenReturn(existing);

        TaxClassResponse response = service.update(classId, new TaxClassRequest("New", new BigDecimal("7.00")));

        assertThat(response.name()).isEqualTo("New");
        assertThat(response.defaultRatePct()).isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void updateRenameToExistingNameThrowsTax8003() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Old")));
        when(taxClassRepository.existsByNameIgnoreCaseAndIdNot("Taken", classId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(classId, new TaxClassRequest("Taken", new BigDecimal("7.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8003"));

        verify(taxClassRepository, never()).save(any());
    }

    @Test
    void updateUnknownClassThrowsTax8001() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(classId, new TaxClassRequest("New", new BigDecimal("7.00"))))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));
    }

    @Test
    void getReturnsResponse() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard")));

        TaxClassResponse response = service.get(classId);

        assertThat(response.id()).isEqualTo(classId);
        assertThat(response.name()).isEqualTo("Standard");
    }

    @Test
    void getUnknownClassThrowsTax8001() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(classId))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));
    }

    @Test
    void listReturnsAllClasses() {
        when(taxClassRepository.findAll()).thenReturn(List.of(taxClass("Standard")));

        List<TaxClassResponse> responses = service.list();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).name()).isEqualTo("Standard");
    }

    @Test
    void deleteWithRatesThrowsTax8004AndKeepsRow() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(taxClass("Standard")));
        when(taxRateRepository.countByClassId(classId)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(classId))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8004"));

        verify(taxClassRepository, never()).save(any());
    }

    @Test
    void deleteEmptyMarksDeletedWithAuditor() {
        TaxClass existing = taxClass("Standard");
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(existing));
        when(taxRateRepository.countByClassId(classId)).thenReturn(0L);
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("admin-1"));

        service.delete(classId);

        ArgumentCaptor<TaxClass> captor = ArgumentCaptor.forClass(TaxClass.class);
        verify(taxClassRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
        assertThat(captor.getValue().getDeletedBy()).isEqualTo("admin-1");
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    void deleteRequiresAuditor() {
        TaxClass existing = taxClass("Standard");
        when(taxClassRepository.findById(classId)).thenReturn(Optional.of(existing));
        when(taxRateRepository.countByClassId(classId)).thenReturn(0L);
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(classId))
            .isInstanceOf(java.util.NoSuchElementException.class);

        verify(taxClassRepository, never()).save(any());
    }

    @Test
    void deleteUnknownClassThrowsTax8001() {
        when(taxClassRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(classId))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("TAX-8001"));

        verify(taxRateRepository, never()).countByClassId(any());
    }

    @Test
    void markDeletedIsSoftDeletablePattern() {
        TaxClass existing = taxClass("Standard");
        assertThat(existing).isInstanceOf(SoftDeletable.class);
    }
}
