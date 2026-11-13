package com.shop.authservice.service.impls;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import com.shop.authservice.entity.PasswordResetToken;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.repository.PasswordResetTokenRepository;
import com.shop.authservice.repository.RoleRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.authservice.service.PasswordResetEmailSender;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * F-057: the password reset log must not enumerate accounts.
 *
 * <p>The unknown-account branch logged the submitted address, so anybody with
 * read access to the log could tell which addresses have an account. The two
 * branches now write the SAME line; the counter keeps the distinction
 * visible to monitoring instead of to the log reader.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplForgotPasswordLoggingTest {

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
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordResetEmailSender passwordResetEmailSender;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private UserServiceImpl userService;

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UserServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("the unknown-account branch logs no address at all")
    void theUnknownAccountBranchLogsNoAddress() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            userService.forgotPassword("nobody@example.com");

            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain("nobody@example.com")
                            .doesNotContain("nobody"));
        } finally {
            ((Logger) LoggerFactory.getLogger(UserServiceImpl.class)).detachAppender(appender);
        }
    }

    @Test
    @DisplayName("both branches write the same line - the log cannot enumerate accounts")
    void bothBranchesWriteTheSameLine() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
            userService.forgotPassword("nobody@example.com");
            String unknownAccountLine = appender.list.get(appender.list.size() - 1).getFormattedMessage();

            appender.list.clear();

            User user = User.builder()
                    .id(UUID.randomUUID())
                    .username("alice")
                    .email("alice@example.com")
                    .build();
            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
            when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            userService.forgotPassword("alice@example.com");
            String existingAccountLine = appender.list.get(appender.list.size() - 1).getFormattedMessage();

            assertThat(unknownAccountLine).isEqualTo(existingAccountLine);
        } finally {
            ((Logger) LoggerFactory.getLogger(UserServiceImpl.class)).detachAppender(appender);
        }
    }

    @Test
    @DisplayName("the unknown-account case stays visible to monitoring")
    void theUnknownAccountCaseStaysVisibleToMonitoring() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        userService.forgotPassword("nobody@example.com");

        assertThat(meterRegistry.counter("auth.password.reset.request", "outcome", "unknown_account").count())
                .isEqualTo(1.0d);
    }
}
