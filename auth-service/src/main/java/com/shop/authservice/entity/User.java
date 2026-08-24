package com.shop.authservice.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.NaturalId;

import java.util.HashSet;
import java.util.UUID;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="user_id", unique = true, nullable = false)
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
