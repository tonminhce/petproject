package com.shop.taxservice.repository;

import com.shop.taxservice.entity.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    Optional<TaxRate> findByTaxClassIdAndCountryAndPostalCode(UUID classId, String country, String postalCode);

    Optional<TaxRate> findByTaxClassIdAndCountryAndPostalCodeIsNull(UUID classId, String country);

    @Query("select r from TaxRate r where r.taxClassId = :classId and r.country = :country "
            + "and (r.postalCode = :postalCode or r.postalCode is null) "
            + "order by case when r.postalCode is null then 1 else 0 end")
    List<TaxRate> findMatchingRates(@Param("classId") UUID classId,
                                    @Param("country") String country,
                                    @Param("postalCode") String postalCode);

    List<TaxRate> findAllByTaxClassId(UUID classId);

    @Query("select count(r) from TaxRate r where r.taxClassId = :classId")
    long countByClassId(@Param("classId") UUID classId);

    /**
     * Duplicate pre-check mirroring the DB unique index on
     * (tax_class_id, country, coalesce(postal_code, '')): null and empty
     * postal codes are treated as equal tiers.
     */
    @Query("select count(r) from TaxRate r where r.taxClassId = :c and r.country = :country " +
           "and coalesce(r.postalCode, '') = coalesce(:postal, '') " +
           "and (:excludeId is null or r.id <> :excludeId)")
    long countDuplicate(@Param("c") UUID c,
                        @Param("country") String country,
                        @Param("postal") String postal,
                        @Param("excludeId") UUID excludeId);
}
