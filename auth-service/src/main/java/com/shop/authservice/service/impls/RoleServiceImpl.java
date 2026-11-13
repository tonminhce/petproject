package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.RoleMapper;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.RoleService;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The role administration of auth-service.
 *
 * <p>F-075: the Keycloak realm is the only place that decides what a token
 * carries. This service used to write the LOCAL role table only, so the
 * admin role API answered 200 while the user's next token still carried no
 * ADMIN - the operator believed a privilege had been granted that never
 * reached the authorization layer.</p>
 *
 * <p>Both operations now touch the realm first and the local mirror second:
 * the mirror may only claim what the realm already granted. The remote call
 * runs OUTSIDE the database transaction (the F-066 lesson: a remote call
 * must not hold a pooled connection open), and only the short local write
 * holds a transaction.</p>
 */
@Service
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final TransactionTemplate transactionTemplate;

    public RoleServiceImpl(RoleRepository roleRepository,
                            UserRepository userRepository,
                            KeycloakAdminClient keycloakAdminClient,
                            TransactionTemplate transactionTemplate) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<Role> findByName(RoleName name) {
        return Optional.of(roleRepository.findByName(name)
                .orElseThrow(() -> BusinessException.notFound("auth.role.not.found.with.name", name)));
    }

    @Override
    public boolean assignRole(UUID userId, String roleName) {
        RoleName name = RoleMapper.toRoleName(roleName);
        AssignmentTarget target = loadAssignmentTarget(userId, name);

        // The realm first, and ALWAYS: the assignment is idempotent there, and
        // repeating it repairs a mirror that drifted away from the realm (a
        // role granted through the Keycloak console, for example).
        keycloakAdminClient.assignRealmRole(target.keycloakUserId(), name.name());

        Boolean changed = transactionTemplate.execute(status -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.id", userId));
            Role role = roleRepository.findByName(name)
                    .orElseThrow(() -> BusinessException.notFound("auth.role.not.found.in.system", roleName));
            if (user.getRoles().contains(role)) {
                return false;
            }
            user.getRoles().add(role);
            userRepository.save(user);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public boolean revokeRole(UUID id, String roleName) {
        RoleName name = RoleMapper.toRoleName(roleName);
        RevokeTarget target = loadRevokeTarget(id, name);
        if (!target.present()) {
            return false;
        }

        keycloakAdminClient.removeRealmRole(target.keycloakUserId(), name.name());

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("auth.user.not.found"));
            if (user.getRoles().removeIf(role -> role.getName().equals(name))) {
                userRepository.save(user);
            }
        });
        return true;
    }

    @Override
    // A1: auth-service yml has open-in-view: false and User.roles is LAZY. Without a tx
    @Transactional(readOnly = true)
    public List<String> getUserRoles(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("auth.user.not.found"));

        List<String> roleNames = new java.util.ArrayList<>();
        user.getRoles().forEach(userRole -> roleNames.add(userRole.getName().toString()));
        return roleNames;
    }

    /**
     * Reads the user and the role in one short transaction - the roles
     * collection is LAZY, so it cannot be read outside a transaction.
     */
    private AssignmentTarget loadAssignmentTarget(UUID userId, RoleName name) {
        AssignmentTarget target = transactionTemplate.execute(status -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> BusinessException.notFound("auth.user.not.found.with.id", userId));
            Role role = roleRepository.findByName(name)
                    .orElseThrow(() -> BusinessException.notFound("auth.role.not.found.in.system", name));
            return new AssignmentTarget(user.getRoles().contains(role), keycloakUserId(user));
        });
        if (target == null) {
            throw BusinessException.of(ErrorCode.AUTH_USER_NOT_FOUND, userId);
        }
        return target;
    }

    private RevokeTarget loadRevokeTarget(UUID userId, RoleName name) {
        RevokeTarget target = transactionTemplate.execute(status -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> BusinessException.notFound("auth.user.not.found"));
            boolean present = user.getRoles().stream()
                    .anyMatch(role -> role.getName().equals(name));
            return new RevokeTarget(present, keycloakUserId(user));
        });
        if (target == null) {
            throw BusinessException.of(ErrorCode.AUTH_USER_NOT_FOUND, userId);
        }
        return target;
    }

    /**
     * The realm user id. Without it there is no Keycloak account to change,
     * so the operation cannot be carried out - it must fail loudly instead
     * of pretending the role was granted.
     */
    private String keycloakUserId(User user) {
        String keycloakUserId = user.getKeycloakUserId();
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw BusinessException.of(ErrorCode.AUTH_USER_KEYCLOAK_ACCOUNT_MISSING, user.getId());
        }
        return keycloakUserId;
    }

    private record AssignmentTarget(boolean alreadyAssigned, String keycloakUserId) {
    }

    private record RevokeTarget(boolean present, String keycloakUserId) {
    }
}
