package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private KeycloakTokenClient keycloakTokenClient;

    @InjectMocks
    private UserServiceImpl userService;

    private UUID userId;
    private Role userRole;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        userRole = new Role(UUID.randomUUID(), RoleName.USER);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RegisterRequest registerRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Alice Wonder");
        req.setUsername("alice");
        req.setPassword("Passw0rd");
        req.setEmail("alice@example.com");
        req.setGender("female");
        req.setPhone("0901234567");
        return req;
    }

    private User newUser() {
        return User.builder()
                .id(userId)
                .fullName("Alice Wonder")
                .username("alice")
                .email("alice@example.com")
                .gender("female")
                .phone("0901234567")
                .build();
    }

    @Test
    void registerCreatesKeycloakUserAndPersistsShadowCopy() {
        RegisterRequest req = registerRequest();
        User user = newUser();
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("0901234567")).thenReturn(false);
        when(keycloakAdminClient.createUser("alice", "alice@example.com", "Alice Wonder", "Passw0rd", List.of("USER")))
                .thenReturn("kc-123");
        when(roleRepository.findByNameIn(List.of("USER"))).thenReturn(Set.of(userRole));
        when(userMapper.toEntity(req)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.register(req);

        assertEquals("kc-123", user.getKeycloakUserId());
        assertTrue(user.getRoles().contains(userRole));
        verify(userRepository).save(user);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        RegisterRequest req = registerRequest();
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.register(req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(keycloakAdminClient, never()).createUser(any(), any(), any(), any(), any());
    }

    @Test
    void registerRollsBackKeycloakUserWhenPersistenceFails() {
        RegisterRequest req = registerRequest();
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.existsByPhone("0901234567")).thenReturn(false);
        when(keycloakAdminClient.createUser(any(), any(), any(), any(), any())).thenReturn("kc-123");
        when(roleRepository.findByNameIn(any())).thenReturn(Set.of(userRole));
        when(userMapper.toEntity(req)).thenReturn(newUser());
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> userService.register(req));

        verify(keycloakAdminClient).deleteUser("kc-123");
    }

    @Test
    void updatePersistsChangedFields() {
        User user = newUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(user))
                .thenReturn(UserResponse.builder().id(userId).fullname("Alice Wonder II").build());

        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Alice Wonder II");

        UserResponse response = userService.update(userId, req);

        assertEquals("Alice Wonder II", user.getFullName());
        assertEquals("Alice Wonder II", response.getFullname());
    }

    @Test
    void updateThrowsNotFoundWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.update(userId, new UpdateUserRequest()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void changePasswordRejectsMismatchedConfirmation() {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("Oldpass1");
        req.setNewPassword("Newpass1");
        req.setConfirmPassword("Newpass2");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void changePasswordVerifiesOldPasswordThenResets() {
        User user = newUser();
        user.setKeycloakUserId("kc-123");
        authenticateAs(user);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(keycloakTokenClient.verifyCredentials("alice", "Oldpass1")).thenReturn(true);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("Oldpass1");
        req.setNewPassword("Newpass1");
        req.setConfirmPassword("Newpass1");

        String result = userService.changePassword(req);

        assertEquals("Password changed successfully", result);
        verify(keycloakAdminClient).resetUserPassword("kc-123", "Newpass1", false);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        User user = newUser();
        authenticateAs(user);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(keycloakTokenClient.verifyCredentials("alice", "Wrong1")).thenReturn(false);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("Wrong1");
        req.setNewPassword("Newpass1");
        req.setConfirmPassword("Newpass1");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.changePassword(req));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void deleteSoftDeletesWhenFound() {
        when(userRepository.softDelete(userId, "alice")).thenReturn(1);

        assertEquals("User deleted successfully", userService.delete(userId, "alice"));
    }

    @Test
    void deleteThrowsNotFoundWhenMissing() {
        when(userRepository.softDelete(userId, "alice")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.delete(userId, "alice"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void restoreRevivesWhenFound() {
        when(userRepository.restore(userId)).thenReturn(1);

        assertEquals("User restored successfully", userService.restore(userId));
    }

    @Test
    void findByUsernameReturnsUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(newUser()));

        assertEquals("alice", userService.findByUsername("alice").getUsername());
    }

    @Test
    void findByUsernameThrowsNotFoundWhenMissing() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.findByUsername("nobody"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void findAllUsersMapsToPage() {
        when(userRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(newUser())));
        when(userMapper.toResponse(any(User.class)))
                .thenReturn(UserResponse.builder().id(userId).username("alice").build());

        Page<UserResponse> page = userService.findAllUsers(0, 10, "id", "ASC");

        assertEquals(1, page.getTotalElements());
        assertEquals("alice", page.getContent().get(0).getUsername());
    }

    private void authenticateAs(User user) {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of("sub", user.getUsername(), "preferred_username", user.getUsername())
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of())
        );
    }
}
