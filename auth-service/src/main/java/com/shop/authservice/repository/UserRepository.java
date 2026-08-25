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

    // ---- Query methods (auto-filtered via @SQLRestriction) ----

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    // ---- Soft-delete operations ----

    /**
     * Soft-deletes a user instead of removing the row. Sets {@code deleted=true},
     * {@code deletedAt=NOW()}, and the actor's id. The row stays in the DB for audit
     * but disappears from every {@code find*} query thanks to {@code @SQLRestriction}.
     *
     * @return number of rows affected (0 if id didn't exist or already deleted)
     */
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

    /**
     * Restores a previously soft-deleted user.
     */
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

    /**
     * Looks up a user INCLUDING soft-deleted ones. Used by admin endpoints that need
     * to inspect or restore deleted records. Bypasses {@code @SQLRestriction} via native SQL.
     */
    @Query(value = "SELECT * FROM users WHERE user_id = :id", nativeQuery = true)
    Optional<User> findByIdIncludingDeleted(@Param("id") UUID id);
}