package com.shop.authservice.service.impls;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;
import com.shop.authservice.entity.User;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.RoleService;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.exception.KeycloakClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-075: the admin role API must reach the Keycloak realm.
 *
 * <p>The realm is the only place that decides what a token carries. The
 * service used to write the local mirror only, so the endpoint answered 200
 * while the user's next token still carried no ADMIN: the operator believed
 * a privilege had been granted that never reached the authorization layer.</p>
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    private RoleServiceImpl roleService;

    private UUID userId;
    private Role managerRole;

    /**
     * A transaction template that runs the callback without a real
     * transaction: the test is about the ORDER of the realm call and the
     * local write, not about the JDBC transaction itself.
     */
    private static final PlatformTransactionManager NO_TX = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // nothing to commit
        }

        @Override
        public void rollback(TransactionStatus status) {
            // nothing to roll back
        }
    };

    @BeforeEach
    void setUp() {
        roleService = new RoleServiceImpl(roleRepository, userRepository, keycloakAdminClient,
                new TransactionTemplate(NO_TX));
        userId = UUID.randomUUID();
        managerRole = new Role(UUID.randomUUID(), RoleName.MANAGER);
    }

    private User userWithRoles(Role... roles) {
        User user = User.builder()
                .id(userId)
                .username("alice")
                .fullName("Alice")
                .email("alice@example.com")
                .keycloakUserId("kc-123")
                .build();
        for (Role role : roles) {
            user.getRoles().add(role);
        }
        return user;
    }

    @Test
    @DisplayName("assigning a role grants it in the Keycloak realm, not only in the local mirror")
    void assigningARoleGrantsItInTheRealm() {
        User user = userWithRoles();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertThat(roleService.assignRole(userId, "MANAGER")).isTrue();

        verify(keycloakAdminClient).assignRealmRole("kc-123", "MANAGER");
        assertThat(user.getRoles()).contains(managerRole);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("the realm is changed before the local mirror is written")
    void theRealmIsChangedBeforeTheLocalMirror() {
        User user = userWithRoles();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        roleService.assignRole(userId, "MANAGER");

        InOrder order = Mockito.inOrder(keycloakAdminClient, userRepository);
        order.verify(keycloakAdminClient).assignRealmRole("kc-123", "MANAGER");
        order.verify(userRepository).save(user);
    }

    @Test
    @DisplayName("a failing realm call leaves the local mirror untouched")
    void aFailingRealmCallLeavesTheLocalMirrorUntouched() {
        User user = userWithRoles();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));
        Mockito.doThrow(new KeycloakClientException("realm down", HttpStatus.SERVICE_UNAVAILABLE))
                .when(keycloakAdminClient).assignRealmRole("kc-123", "MANAGER");

        assertThatThrownBy(() -> roleService.assignRole(userId, "MANAGER"))
                .isInstanceOf(KeycloakClientException.class);

        assertThat(user.getRoles()).doesNotContain(managerRole);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("revoking a role removes it from the realm and from the mirror")
    void revokingARoleRemovesItFromTheRealm() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(roleService.revokeRole(userId, "MANAGER")).isTrue();

        verify(keycloakAdminClient).removeRealmRole("kc-123", "MANAGER");
        assertThat(user.getRoles()).doesNotContain(managerRole);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("a failing realm call leaves the role in the local mirror")
    void aFailingRealmCallLeavesTheRoleInTheMirror() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        Mockito.doThrow(new KeycloakClientException("realm down", HttpStatus.SERVICE_UNAVAILABLE))
                .when(keycloakAdminClient).removeRealmRole("kc-123", "MANAGER");

        assertThatThrownBy(() -> roleService.revokeRole(userId, "MANAGER"))
                .isInstanceOf(KeycloakClientException.class);

        assertThat(user.getRoles()).contains(managerRole);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a user without a Keycloak account cannot be granted a role")
    void aUserWithoutAKeycloakAccountCannotBeGrantedARole() {
        User user = User.builder()
                .id(userId)
                .username("local-only")
                .fullName("Local Only")
                .email("local-only@example.com")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertThatThrownBy(() -> roleService.assignRole(userId, "MANAGER"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("AUTH-1011"));

        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    @DisplayName("findByName returns the role")
    void findByNameReturnsRole() {
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertThat(roleService.findByName(RoleName.MANAGER)).contains(managerRole);
    }

    @Test
    @DisplayName("findByName throws not found when missing")
    void findByNameThrowsNotFoundWhenMissing() {
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.findByName(RoleName.MANAGER))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("assignRole returns false when the mirror already holds the role")
    void assignRoleReturnsFalseWhenTheMirrorAlreadyHoldsTheRole() {
        User user = userWithRoles(managerRole);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.of(managerRole));

        assertThat(roleService.assignRole(userId, "MANAGER")).isFalse();

        // the realm is still called: the assignment is idempotent there and
        // repairs a mirror that drifted away from the realm
        verify(keycloakAdminClient).assignRealmRole("kc-123", "MANAGER");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("assignRole throws not found when the user is missing")
    void assignRoleThrowsNotFoundWhenTheUserIsMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.assignRole(userId, "MANAGER"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    @DisplayName("assignRole throws not found when the role is not in the system")
    void assignRoleThrowsNotFoundWhenTheRoleIsNotInTheSystem() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));
        when(roleRepository.findByName(RoleName.MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.assignRole(userId, "MANAGER"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    @DisplayName("assignRole throws bad request for the fictional PM role")
    void assignRoleThrowsBadRequestForTheFictionalPmRole() {
        // the catalogue rejects PM before any repository is touched
        assertThatThrownBy(() -> roleService.assignRole(userId, "PM"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(roleRepository, never()).findByName(any());
    }

    @Test
    @DisplayName("revokeRole returns false when the mirror does not hold the role")
    void revokeRoleReturnsFalseWhenTheMirrorDoesNotHoldTheRole() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles()));

        assertThat(roleService.revokeRole(userId, "MANAGER")).isFalse();

        verifyNoInteractions(keycloakAdminClient);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("getUserRoles returns the role names")
    void getUserRolesReturnsTheRoleNames() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles(managerRole)));

        assertThat(roleService.getUserRoles(userId)).isEqualTo(List.of("MANAGER"));
    }
}
