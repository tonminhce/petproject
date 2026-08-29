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
    private Role pmRole;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        pmRole = new Role(UUID.randomUUID(), RoleName.PM);
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
        when(roleRepository.findByName(RoleName.PM)).thenReturn(Optional.of(pmRole));

        assertEquals(pmRole, roleService.findByName(RoleName.PM).orElseThrow());
    }

    @Test
    void findByNameThrowsNotFoundWhenMissing() {
        when(roleRepository.findByName(RoleName.PM)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.findByName(RoleName.PM));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleAddsWhenAbsent() {
        User user = userWithRoles();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.PM)).thenReturn(Optional.of(pmRole));

        assertTrue(roleService.assignRole(userId, "PM"));
        assertTrue(user.getRoles().contains(pmRole));
        verify(userRepository).save(user);
    }

    @Test
    void assignRoleReturnsFalseWhenAlreadyAssigned() {
        User user = userWithRoles(pmRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.PM)).thenReturn(Optional.of(pmRole));

        assertFalse(roleService.assignRole(userId, "PM"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void assignRoleThrowsNotFoundWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "PM"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleThrowsNotFoundWhenRoleNotInSystem() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));
        when(roleRepository.findByName(RoleName.PM)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "PM"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void assignRoleThrowsBadRequestForUnsupportedRoleName() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.assignRole(userId, "SUPERADMIN"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void revokeRoleRemovesWhenPresent() {
        User user = userWithRoles(pmRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertTrue(roleService.revokeRole(userId, "PM"));
        assertFalse(user.getRoles().contains(pmRole));
        verify(userRepository).save(user);
    }

    @Test
    void revokeRoleReturnsFalseWhenAbsent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        assertFalse(roleService.revokeRole(userId, "PM"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getUserRolesReturnsRoleNames() {
        User user = userWithRoles(pmRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertEquals(List.of("PM"), roleService.getUserRoles(userId));
    }
}
