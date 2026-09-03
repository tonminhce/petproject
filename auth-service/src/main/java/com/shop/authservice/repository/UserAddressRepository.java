package com.shop.authservice.repository;

import com.shop.authservice.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    List<UserAddress> findByUserIdOrderByIsDefaultDescCreatedAtDesc(UUID userId);

    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE UserAddress a SET a.isDefault = false WHERE a.userId = :userId")
    void resetDefaultByUserId(@Param("userId") UUID userId);
}
