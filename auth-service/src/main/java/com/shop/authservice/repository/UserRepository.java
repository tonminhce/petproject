package com.shop.authservice.repository;

import com.shop.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    @Modifying
    @Query("""
            UPDATE User u
               SET u.deleted = true,
                   u.deletedAt = CURRENT_TIMESTAMP,
                   u.deletedBy = :deletedBy
             WHERE u.id = :id
               AND u.deleted = false
            """)
    int softDelete(@Param("id") UUID id, @Param("deletedBy") String deletedBy);

    @Modifying
    @Query("""
            UPDATE User u
               SET u.deleted = false,
                   u.deletedAt = NULL,
                   u.deletedBy = NULL
             WHERE u.id = :id
               AND u.deleted = true
            """)
    int restore(@Param("id") UUID id);

    @Query(value = "SELECT * FROM users WHERE user_id = :id", nativeQuery = true)
    Optional<User> findByIdIncludingDeleted(@Param("id") UUID id);
}
