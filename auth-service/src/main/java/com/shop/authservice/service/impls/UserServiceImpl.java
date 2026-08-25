package com.shop.authservice.service.impls;

import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.UserService;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import com.shop.common.logging.LogPerformance;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakTokenClient keycloakTokenClient;

    @Override
    @Transactional
    @LogPerformance(title = "Register user", logInput = true)
    public User register(RegisterRequest request) {
        validateUniqueConstraints(request);

        String keycloakUserId = createKeycloakUser(request);

        try {
            User user = buildUserFromRequest(request, keycloakUserId);
            return userRepository.save(user);
        } catch (RuntimeException e) {
            rollbackKeycloakUser(keycloakUserId);
            throw e;
        }
    }

    @Override
    @Transactional
    public UserResponse update(UUID userId, UpdateUserRequest request) {
        User user = findUserOrThrow(userId);
        updateUserFields(user, request);
        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public String changePassword(ChangePasswordRequest request) {
        validatePasswordMatch(request);

        User currentUser = getCurrentAuthenticatedUser();
        String username = currentUser.getUsername();

        verifyOldPassword(username, request.getOldPassword());

        keycloakAdminClient.resetUserPassword(
                currentUser.getKeycloakUserId(),
                request.getNewPassword(),
                false
        );

        return "Password changed successfully";
    }

    @Override
    @Transactional
    public String delete(UUID id, String deletedBy) {
        int affected = userRepository.softDelete(id, deletedBy);
        if (affected == 0) {
            throw BusinessException.notFound("auth.user.not.found.for.update", id);
        }
        return "User deleted successfully";
    }

    @Override
    @Transactional
    public String restore(UUID id) {
        int affected = userRepository.restore(id);
        if (affected == 0) {
            throw BusinessException.notFound("auth.user.not.found.for.update", id);
        }
        return "User restored successfully";
    }

    @Override
    public User findById(UUID userId) {
        return findUserOrThrow(userId);
    }

    @Override
    public User findByUsername(String userName) {
        return userRepository.findByUsername(userName)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.username", userName));
    }

    @Override
    @LogPerformance(title = "List users")
    public Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortOrder), sortBy);
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        return userRepository.findAll(pageRequest).map(userMapper::toResponse);
    }

    // ==================================
    // Private helper methods
    // ==================================

    private void validateUniqueConstraints(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw BusinessException.conflict("auth.username.exists", request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw BusinessException.conflict("auth.email.exists", request.getEmail());
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw BusinessException.conflict("auth.phone.exists", request.getPhone());
        }
    }

    private String createKeycloakUser(RegisterRequest request) {
        List<String> roles = extractRoles(request.getRoles());
        return keycloakAdminClient.createUser(
                request.getUsername(),
                request.getEmail(),
                request.getFullName(),
                request.getPassword(),
                roles
        );
    }

    private List<String> extractRoles(Set<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            return List.of("USER");
        }
        return requestedRoles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .toList();
    }

    private User buildUserFromRequest(RegisterRequest request, String keycloakUserId) {
        User user = userMapper.toEntity(request);
        user.setKeycloakUserId(keycloakUserId);
        user.setRoles(resolveRoles(request.getRoles()));
        return user;
    }

    private Set<Role> resolveRoles(Set<String> requestedRoles) {
        List<String> roleNames = extractRoles(requestedRoles);
        return roleRepository.findByNameIn(roleNames);
    }

    private void rollbackKeycloakUser(String keycloakUserId) {
        try {
            keycloakAdminClient.deleteUser(keycloakUserId);
        } catch (Exception e) {
            // Log but don't throw - original exception is more important
        }
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.for.update", userId));
    }

    private void updateUserFields(User user, UpdateUserRequest request) {
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
    }

    private void validatePasswordMatch(ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw BusinessException.badRequest("auth.password.mismatch");
        }
    }

    private User getCurrentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw BusinessException.unauthorized("auth.not.authenticated");
        }

        Jwt jwt = (Jwt) authentication.getPrincipal();
        String username = jwt.getSubject();

        return userRepository.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.username", username));
    }

    private void verifyOldPassword(String username, String oldPassword) {
        boolean isValid = keycloakTokenClient.verifyCredentials(username, oldPassword);
        if (!isValid) {
            throw BusinessException.unauthorized("auth.invalid.credentials");
        }
    }
}
