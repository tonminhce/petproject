package com.shop.taxservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.entity.TaxClass;
import com.shop.taxservice.entity.TaxRate;
import com.shop.taxservice.repository.TaxClassRepository;
import com.shop.taxservice.repository.TaxRateRepository;
import com.shop.taxservice.service.TaxCalculationService;
import com.shop.taxservice.service.TaxCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaxCalculationServiceImpl implements TaxCalculationService {

    private final TaxClassRepository taxClassRepository;
    private final TaxRateRepository taxRateRepository;

    @Override
    @Transactional(readOnly = true)
    public TaxCalculateResponse calculate(TaxCalculateRequest req) {
        TaxClass taxClass = taxClassRepository.findById(req.taxClassId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, req.taxClassId()));

        BigDecimal ratePct = resolveRatePct(req.taxClassId(), req.country(), normalize(req.postalCode()))
            .orElse(taxClass.getDefaultRatePct());

        if (ratePct == null) {
            throw BusinessException.of(ErrorCode.NO_MATCHING_RATE, req.country() + " " + req.postalCode());
        }
        return TaxCalculator.calculate(req.amount(), ratePct);
    }

    private Optional<BigDecimal> resolveRatePct(UUID classId, String country, String postalCode) {
        return taxRateRepository.findMatchingRates(classId, country, postalCode).stream()
            .findFirst()
            .map(TaxRate::getRatePct);
    }

    private String normalize(String postalCode) {
        return postalCode == null || postalCode.isBlank() ? null : postalCode;
    }
}
