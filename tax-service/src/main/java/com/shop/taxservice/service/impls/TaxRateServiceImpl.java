package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.taxservice.dto.request.TaxRateRequest;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.entity.TaxRate;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import com.shop.taxservice.service.TaxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaxRateServiceImpl implements TaxRateService {

    private final TaxRateRepository taxRateRepository;
    private final TaxClassRepository taxClassRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional
    public TaxRateResponse create(TaxRateRequest request) {
        requireTaxClass(request.taxClassId());
        String postalCode = normalize(request.postalCode());
        requireNoDuplicate(request.taxClassId(), request.country(), postalCode, null);
        TaxRate taxRate = TaxRate.builder()
            .taxClassId(request.taxClassId())
            .country(request.country())
            .postalCode(postalCode)
            .ratePct(request.ratePct())
            .build();
        return toResponse(taxRateRepository.save(taxRate));
    }

    @Override
    @Transactional
    public TaxRateResponse update(UUID id, TaxRateRequest request) {
        TaxRate taxRate = taxRateRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_RATE_NOT_FOUND, id));
        requireTaxClass(request.taxClassId());
        String postalCode = normalize(request.postalCode());
        requireNoDuplicate(request.taxClassId(), request.country(), postalCode, id);
        taxRate.setTaxClassId(request.taxClassId());
        taxRate.setCountry(request.country());
        taxRate.setPostalCode(postalCode);
        taxRate.setRatePct(request.ratePct());
        return toResponse(taxRateRepository.save(taxRate));
    }

    @Override
    @Transactional(readOnly = true)
    public TaxRateResponse get(UUID id) {
        return taxRateRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_RATE_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxRateResponse> list(UUID classId) {
        return taxRateRepository.findAllByTaxClassId(classId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        TaxRate taxRate = taxRateRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_RATE_NOT_FOUND, id));
        taxRate.markDeleted(auditorAware.getCurrentAuditor().orElseThrow());
        taxRateRepository.save(taxRate);
    }

    private void requireTaxClass(UUID classId) {
        taxClassRepository.findById(classId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, classId));
    }

    private void requireNoDuplicate(UUID classId, String country, String postalCode, UUID excludeId) {
        if (taxRateRepository.countDuplicate(classId, country, postalCode, excludeId) > 0) {
            throw BusinessException.of(ErrorCode.DUPLICATE_TAX_RATE, country + " " + postalCode);
        }
    }

    private String normalize(String postalCode) {
        return postalCode == null || postalCode.isBlank() ? null : postalCode;
    }

    private TaxRateResponse toResponse(TaxRate taxRate) {
        return new TaxRateResponse(taxRate.getId(), taxRate.getTaxClassId(), taxRate.getCountry(),
            taxRate.getPostalCode(), taxRate.getRatePct());
    }
}
