package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.taxservice.dto.request.TaxClassRequest;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.entity.TaxClass;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import com.shop.taxservice.service.TaxClassService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaxClassServiceImpl implements TaxClassService {

    private final TaxClassRepository taxClassRepository;
    private final TaxRateRepository taxRateRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional
    public TaxClassResponse create(TaxClassRequest request) {
        if (taxClassRepository.findByNameIgnoreCaseAndDeletedFalse(request.name()).isPresent()) {
            throw BusinessException.of(ErrorCode.DUPLICATE_TAX_RATE, request.name());
        }
        TaxClass taxClass = TaxClass.builder()
            .name(request.name())
            .defaultRatePct(request.defaultRatePct())
            .build();
        return toResponse(taxClassRepository.save(taxClass));
    }

    @Override
    @Transactional
    public TaxClassResponse update(UUID id, TaxClassRequest request) {
        TaxClass taxClass = taxClassRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, id));
        if (!request.name().equals(taxClass.getName())
                && taxClassRepository.existsByNameIgnoreCaseAndDeletedFalseAndIdNot(request.name(), id)) {
            throw BusinessException.of(ErrorCode.DUPLICATE_TAX_RATE, request.name());
        }
        taxClass.setName(request.name());
        taxClass.setDefaultRatePct(request.defaultRatePct());
        return toResponse(taxClassRepository.save(taxClass));
    }

    @Override
    @Transactional(readOnly = true)
    public TaxClassResponse get(UUID id) {
        return taxClassRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxClassResponse> list() {
        return taxClassRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        TaxClass taxClass = taxClassRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, id));
        if (taxRateRepository.countByClassId(id) > 0) {
            throw BusinessException.of(ErrorCode.TAX_CLASS_IN_USE, id);
        }
        taxClass.markDeleted(auditorAware.getCurrentAuditor().orElseThrow());
        taxClassRepository.save(taxClass);
    }

    private TaxClassResponse toResponse(TaxClass taxClass) {
        return new TaxClassResponse(taxClass.getId(), taxClass.getName(), taxClass.getDefaultRatePct());
    }
}
