package com.shop.authservice.repository;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(RoleName name);

    Set<Role> findByNameIn(Collection<RoleName> names);
}
