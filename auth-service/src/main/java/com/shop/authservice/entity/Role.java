package com.shop.authservice.entity;

import com.shop.authservice.config.RoleName;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.NaturalId;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", unique = true, nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @NaturalId
    @Column(name = "role_name", length = 60)
    private RoleName name;
}