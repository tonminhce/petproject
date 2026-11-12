package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.request.ResetPasswordRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.PasswordResetToken;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.repository.PasswordResetTokenRepository;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.PasswordResetEmailSender;
import com.shop.authservice.service.UserService;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import com.shop.common.logging.LogPerformance;
import com.shop.common.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
        "id", "username", "email", "phone", "fullName", "createdAt", "updatedAt"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakTokenClient keycloakTokenClient;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetEmailSender passwordResetEmailSender;

    @Override
    @LogPerformance(title = "Register user")
    public UserResponse register(RegisterRequest request) {
        validateUniqueConstraints(request);

        // F-050: the local row is inserted BEFORE the Keycloak user exists, so
        // the unique index is the arbiter of a concurrent registration. The
        // loser of the race fails on the insert and never creates a Keycloak
        // user at all - with the previous order both registrations created a
        // Keycloak user and the local insert of the loser only failed
        // afterwards.
        User user = buildUserFromRequest(request, null);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the race on the unique index: the
            // caller gets the catalogue conflict, not a raw constraint
            // violation.
            throw conflictForDuplicate(e, request);
        }

        String keycloakUserId;
        try {
            keycloakUserId = createKeycloakUser(request);
        } catch (RuntimeException e) {
            // The local row must not keep the username hostage when the
            // identity provider refused the user.
            rollbackLocalUser(user.getId());
            throw e;
        }

        user.setKeycloakUserId(keycloakUserId);
        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    /**
     * The catalogue conflict for a unique-constraint violation.
     *
     * <p>The constraint name decides the code, so the caller learns WHICH
     * field is taken (username / email / phone) instead of a generic
     * conflict.</p>
     */
    private BusinessException conflictForDuplicate(DataIntegrityViolationException e, RegisterRequest request) {
        String detail = rootMessage(e);
        if (detail.contains("username")) {
            return BusinessException.conflict("auth.username.exists", request.username());
        }
        if (detail.contains("email")) {
            return BusinessException.conflict("auth.email.exists", request.email());
        }
        if (detail.contains("phone")) {
            return BusinessException.conflict("auth.phone.exists", request.phone());
        }
        return BusinessException.conflict("error.conflict");
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage()).toLowerCase();
    }

    /** Removes the local row of a registration whose Keycloak user could not be created. */
    private void rollbackLocalUser(UUID userId) {
        try {
            userRepository.deleteById(userId);
        } catch (Exception e) {
            log.error("Failed to roll back the local user {} after the Keycloak user could not be created", userId, e);
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
        // rather than reported as a failed password change.
        revokeSessions(currentUser.getKeycloakUserId());

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
    public UserResponse findById(UUID userId) {
        return userMapper.toResponse(findUserOrThrow(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findByUsername(String userName) {
        User user = userRepository.findByUsername(userName)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.username", userName));
        return userMapper.toResponse(user);
    }

    @Override
    @LogPerformance(title = "List users")
    @Transactional(readOnly = true)
    public Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String safeSortBy = (sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy)) ? sortBy : "id";
        Sort.Direction direction = "DESC".equalsIgnoreCase(sortOrder) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, safeSortBy);
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, sort);
        return userRepository.findAll(pageRequest).map(userMapper::toResponse);
    }

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
        List<String> roles = extractRoles(request.roles()).stream().map(RoleName::name).toList();
        return keycloakAdminClient.createUser(
                request.username(),
                request.email(),
                request.fullName(),
                request.password(),
                roles
        );
    }

    /**
     * Server-side whitelist of role names assignable through the PUBLIC sign-up
     * endpoint. Only {@code USER} is allowed; {@code ADMIN}, {@code SERVICE},
     * {@code MANAGER} must be assigned via an authenticated admin endpoint (or via
     * Keycloak directly). Unknown values from the request are silently dropped — not
     * rejected with 400 — so existing clients that still send the field keep working.
     */
    private static final Set<String> SELF_REGISTRATION_ALLOWED_ROLES = Set.of("USER");

    private List<RoleName> extractRoles(Set<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            return List.of(RoleName.USER);
        }
        List<RoleName> allowed = requestedRoles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .filter(SELF_REGISTRATION_ALLOWED_ROLES::contains)
                .map(RoleName::valueOf)
                .distinct()
                .toList();
        if (allowed.isEmpty()) {
            // roles: they could sign in, but every @PreAuthorize rule failed
            // and the account was permanently broken without operator
            return List.of(RoleName.USER);
        }
        return allowed;
    }

    private User buildUserFromRequest(RegisterRequest request, String keycloakUserId) {
        User user = userMapper.toEntity(request);
        user.setKeycloakUserId(keycloakUserId);
        user.setRoles(resolveRoles(request.roles()));
        return user;
    }

    private Set<Role> resolveRoles(Set<String> requestedRoles) {
        List<RoleName> roleNames = extractRoles(requestedRoles);
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

    @Override
    @Transactional
    public void forgotPassword(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        var userOpt = userRepository.findByEmail(email.trim());
        if (userOpt.isEmpty()) {
            log.info("Password reset requested for non-existent email: {}", email);
            return;
        }
        User user = userOpt.get();
        String rawToken = UUID.randomUUID().toString().replace("-", "");
        String tokenHash = hashToken(rawToken);

        passwordResetTokenRepository.deleteByUserId(user.getId());

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .used(false)
                .createdAt(Instant.now())
                .build();
        passwordResetTokenRepository.save(resetToken);
        passwordResetEmailSender.sendResetEmail(user.getEmail(), user.getUsername(), rawToken);
        log.info("Password reset token generated successfully for user {}", user.getUsername());
    }

    @Override
    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw BusinessException.badRequest("auth.password.mismatch");
        }
        String tokenHash = hashToken(request.token());
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedFalse(tokenHash)
                .orElseThrow(() -> BusinessException.badRequest("auth.token.invalid.or.expired"));

        if (resetToken.isExpired()) {
            throw BusinessException.badRequest("auth.token.invalid.or.expired");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found", resetToken.getUserId()));

        // F-053: the token is consumed BEFORE the password is changed, and the
        // consumption is a conditional update - only one redemption can win the
        // race. Consuming first also means a crash between the two steps
        // cannot leave a token that is still redeemable.
        if (passwordResetTokenRepository.consumeIfUnused(tokenHash) != 1) {
            // another redemption won the race: the token is single-use
            throw BusinessException.badRequest("auth.token.invalid.or.expired");
        }

        keycloakAdminClient.resetUserPassword(user.getKeycloakUserId(), request.newPassword(), false);
        // a reset performed because the password may have been compromised must
        revokeSessions(user.getKeycloakUserId());

        log.info("Password successfully reset for user {}", user.getUsername());
        return "Password reset successfully";
    }

    /**
     * Revoke every Keycloak session for a user. Best-effort by design: the
     * credential change has already been applied, so a failure here must not
     * be reported as a failed password change. It is logged at ERROR so the
     * operator can see that the old sessions survived.
     */
    private void revokeSessions(String keycloakUserId) {
        try {
            keycloakAdminClient.logoutUser(keycloakUserId);
        } catch (RuntimeException ex) {
            log.error("Password changed but the existing sessions could not be revoked for user {} - "
                    + "previously issued refresh tokens may still be usable", keycloakUserId, ex);
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
