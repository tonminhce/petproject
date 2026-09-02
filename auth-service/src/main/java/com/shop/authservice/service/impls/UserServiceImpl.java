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
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.shop.common.logging.LogPerformance;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakTokenClient keycloakTokenClient;

    @Override
    // C2 fix — logInput=true was a latent password-leak vector: RegisterRequest
    // currently has no @ToString so toString() returns the identity hash and the
    // password stays out of the log, but any future refactor that adds @ToString
    // or switches to a record would start logging plaintext passwords via the
    // perfLogger. Drop logInput rather than rely on the DTO's current shape.
    @LogPerformance(title = "Register user")
    public User register(RegisterRequest request) {
        validateUniqueConstraints(request);

        String keycloakUserId = createKeycloakUser(request);

        try {
            User user = buildUserFromRequest(request, keycloakUserId);
            return userRepository.saveAndFlush(user);
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
    // A7: no DB write — only Keycloak HTTP. Holding a tx across an external call would
    // keep a DB connection pinned for the round-trip (R5 connection-held-over-network).
    public String changePassword(ChangePasswordRequest request) {
        validatePasswordMatch(request);

        User currentUser = getCurrentAuthenticatedUser();
        String username = currentUser.getUsername();

        verifyOldPassword(username, request.oldPassword());

        keycloakAdminClient.resetUserPassword(
                currentUser.getKeycloakUserId(),
                request.newPassword(),
                false
        );

        return "Password changed successfully";
    }

    @Override
    @Transactional
    public String delete(UUID id, String deletedBy) {
        User user = findUserOrThrow(id);
        keycloakAdminClient.disableUser(user.getKeycloakUserId());
        int affected = userRepository.softDelete(id, deletedBy);
        if (affected == 0) {
            throw BusinessException.notFound("auth.user.not.found.for.update", id);
        }
        return "User deleted successfully";
    }

    @Override
    @Transactional
    public String restore(UUID id) {
        User user = userRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.for.update", id));
        keycloakAdminClient.enableUser(user.getKeycloakUserId());
        int affected = userRepository.restore(id);
        if (affected == 0) {
            throw BusinessException.notFound("auth.user.not.found.for.update", id);
        }
        return "User restored successfully";
    }

    @Override
    @Transactional(readOnly = true)
    public User findById(UUID userId) {
        return findUserOrThrow(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public User findByUsername(String userName) {
        return userRepository.findByUsername(userName)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.username", userName));
    }

    @Override
    @LogPerformance(title = "List users")
    @Transactional(readOnly = true)
    public Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder) {
        // A5: clamp page/size so callers can't pass 0 / negatives (IllegalArgumentException → 500)
        // or unbounded sizes (DoS via full-table materialisation). Mirrors fleet PageableConstant cap.
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Sort sort = Sort.by(Sort.Direction.fromString(sortOrder), sortBy);
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, sort);
        return userRepository.findAll(pageRequest).map(userMapper::toResponse);
    }

    // ==================================
    // Private helper methods
    // ==================================

    private void validateUniqueConstraints(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw BusinessException.conflict("auth.username.exists", request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw BusinessException.conflict("auth.email.exists", request.email());
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw BusinessException.conflict("auth.phone.exists", request.phone());
        }
    }

    private String createKeycloakUser(RegisterRequest request) {
        List<String> roles = extractRoles(request.roles());
        return keycloakAdminClient.createUser(
                request.username(),
                request.email(),
                request.fullName(),
                request.password(),
                roles
        );
    }

    /**
     * C1 — server-side whitelist of role names assignable through the PUBLIC sign-up
     * endpoint. Only {@code USER} is allowed; {@code ADMIN}, {@code SERVICE},
     * {@code MANAGER} must be assigned via an authenticated admin endpoint (or via
     * Keycloak directly). Unknown values from the request are silently dropped — not
     * rejected with 400 — so existing clients that still send the field keep working.
     */
    private static final Set<String> SELF_REGISTRATION_ALLOWED_ROLES = Set.of("USER");

    private List<String> extractRoles(Set<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            return List.of("USER");
        }
        return requestedRoles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .filter(SELF_REGISTRATION_ALLOWED_ROLES::contains)
                .distinct()
                .toList();
    }

    private User buildUserFromRequest(RegisterRequest request, String keycloakUserId) {
        User user = userMapper.toEntity(request);
        user.setKeycloakUserId(keycloakUserId);
        user.setRoles(resolveRoles(request.roles()));
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
            log.error("Failed to roll back Keycloak user {}", keycloakUserId, e);
        }
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.for.update", userId));
    }

    private void updateUserFields(User user, UpdateUserRequest request) {
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.avatar() != null) {
            user.setAvatar(request.avatar());
        }
    }

    private void validatePasswordMatch(ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw BusinessException.badRequest("auth.password.mismatch");
        }
    }

    private User getCurrentAuthenticatedUser() {
        AuthenticatedUser authenticatedUser = AuthenticatedUser.requireCurrent();
        String username = authenticatedUser.username();

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
