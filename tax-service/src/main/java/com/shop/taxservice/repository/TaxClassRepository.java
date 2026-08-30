package com.shop.taxservice.repository;

import com.shop.taxservice.entity.TaxClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaxClassRepository extends JpaRepository<TaxClass, UUID> {

    Optional<TaxClass> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
