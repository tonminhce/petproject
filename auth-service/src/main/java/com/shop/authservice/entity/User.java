package com.shop.authservice.entity;

import com.shop.common.core.data.SoftDeletable;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.SQLRestriction;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

@Entity
@Table(name = "users")
// A9: uniqueness on user_name / email / phone_number is enforced by PARTIAL unique
// indexes (Liquibase changeset 003) that ignore soft-deleted rows. JPA's
// `@UniqueConstraint` here would create hard constraints that block re-registration
// of a soft-deleted user — see auth review finding "soft-delete + re-register kills
// identity forever".
// @SQLRestriction injects "AND deleted = false" into EVERY query Hibernate writes
// for this entity. Cannot be bypassed accidentally — only by raw native SQL.
@SQLRestriction("deleted = false")
public class User extends SoftDeletable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id", unique = true, nullable = false)
    private UUID id;

    @NotBlank(message = "Full name must not be blank")
    @Size(min = 3, max = 100, message = "Full name must be between 3 and 100 characters")
    @Column(name = "full_name")
    private String fullName;

    @NotBlank(message = "Username must not be blank")
    @Size(min = 3, max = 100, message = "Username must be between 3 and 100 characters")
    @Column(name = "user_name")
    private String username;

    @NaturalId
    @NotBlank
    @Size(max = 50)
    @Email(message = "Input must be in Email format")
    @Column(name = "email")
    private String email;

    @NotBlank(message = "Gender must not be blank")
    @Column(name = "gender", nullable = false)
    private String gender;

    @Pattern(regexp = "^\\+84[0-9]{9,10}$|^0[0-9]{9,10}$", message = "The phone number is not in the correct format")
    @Size(min = 10, max = 11, message = "Phone number must be between 10 and 11 characters")
    @Column(name = "phone_number", unique = true)
    private String phone;

    @Pattern(regexp = "^(https?://)\\S+$", message = "Avatar URL must be a valid HTTP or HTTPS URL")
    private String avatar;

    @Column(name = "keycloak_user_id", unique = true)
    private String keycloakUserId;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();
}