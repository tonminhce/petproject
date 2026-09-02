package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.RoleMapper;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.RoleService;
import com.shop.common.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public RoleServiceImpl(RoleRepository roleRepository, UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    @Override
    public Optional<Role> findByName(RoleName name) {
        return Optional.of(roleRepository.findByName(name)
                .orElseThrow(() -> BusinessException.notFound("auth.role.not.found.with.name", name)));
    }

    @Transactional
    @Override
    public boolean assignRole(UUID userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.id", userId));

        Role role = roleRepository.findByName(RoleMapper.toRoleName(roleName))
                .orElseThrow(() -> BusinessException.notFound("auth.role.not.found.in.system", roleName));

        if (user.getRoles().contains(role)) {
            return false;
        }

        user.getRoles().add(role);
        userRepository.save(user);
        return true;
    }

    @Transactional
    @Override
    public boolean revokeRole(UUID id, String roleName) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found"));

        if (user.getRoles().removeIf(role -> role.getName().equals(RoleMapper.toRoleName(roleName)))) {
            userRepository.save(user);
            return true;
        }
        return false;
    }

    @Override
    // A1: auth-service yml has open-in-view: false and User.roles is LAZY. Without a tx
    // around this read, `user.getRoles()` would throw LazyInitializationException.
    @Transactional(readOnly = true)
    public List<String> getUserRoles(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found"));

        List<String> roleNames = new ArrayList<>();
        user.getRoles().forEach(userRole -> roleNames.add(userRole.getName().toString()));
        return roleNames;
    }
}
