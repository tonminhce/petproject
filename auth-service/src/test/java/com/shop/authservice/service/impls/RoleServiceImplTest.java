package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.common.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RoleServiceImpl roleService;

    private UUID userId;
    private Role managerRole;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        managerRole = new Role(UUID.randomUUID(), RoleName.MANAGER);
    }

    private User userWithRoles(Role... roles) {
        User user = User.builder()
                .id(userId)
                .username("alice")
                .fullName("Alice")
                .email("alice@example.com")
                .build();
        for (Role role : roles) {
            user.getRoles().add(role);
        }
        return user;
    }

    @Test
    void findByNameReturnsRole() {
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertEquals(managerRole, roleService.findByName(RoleName.MANAGER).orElseThrow());
    }

    @Test
    void findByNameThrowsNotFoundWhenMissing() {
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.findByName(RoleName.MANAGER));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleAddsWhenAbsent() {
        User user = userWithRoles();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertTrue(roleService.assignRole(userId, "MANAGER"));
        assertTrue(user.getRoles().contains(managerRole));
        verify(userRepository).save(user);
    }

    @Test
    void assignRoleReturnsFalseWhenAlreadyAssigned() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertFalse(roleService.assignRole(userId, "MANAGER"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void assignRoleThrowsNotFoundWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "MANAGER"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleThrowsNotFoundWhenRoleNotInSystem() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "MANAGER"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleThrowsBadRequestForTheFictionalPmRole() {
        // F-056: PM was never a Keycloak realm role - granting it changed
        // nothing at runtime, so the catalogue no longer accepts it.
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "PM"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void assignRoleThrowsBadRequestForUnsupportedRoleName() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "SUPERADMIN"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void revokeRoleRemovesWhenPresent() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertTrue(roleService.revokeRole(userId, "MANAGER"));
        assertFalse(user.getRoles().contains(managerRole));
        verify(userRepository).save(user);
    }

    @Test
    void revokeRoleReturnsFalseWhenAbsent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        assertFalse(roleService.revokeRole(userId, "MANAGER"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getUserRolesReturnsRoleNames() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertEquals(List.of("MANAGER"), roleService.getUserRoles(userId));
    }
}
