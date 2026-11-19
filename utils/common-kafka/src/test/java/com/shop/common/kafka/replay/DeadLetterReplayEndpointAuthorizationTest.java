package com.shop.common.kafka.replay;

import com.shop.common.kafka.contract.KafkaTopicContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterReplayEndpointAuthorizationTest {

    private static final String CONTRACTED_DEAD_LETTER_TOPIC =
            KafkaTopicContract.SHOP_ORDER_LIFECYCLE + "-dlt";
    private static final String UNCONTRACTED_DEAD_LETTER_TOPIC = "not-a-contracted-topic-dlt";

    private DeadLetterReplayService replayService;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        replayService = mock(DeadLetterReplayService.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DeadLetterReplayService.class, () -> replayService);
        context.register(SecuredEndpointConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void theReplayIsRejectedForAnUnauthenticatedCaller() {
        DeadLetterReplayEndpoint endpoint = context.getBean(DeadLetterReplayEndpoint.class);

        assertThatThrownBy(() -> endpoint.replay(request(CONTRACTED_DEAD_LETTER_TOPIC)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verify(replayService, never()).replay(anyString(), anyInt(), anyInt());
    }

    @Test
    void theReplayIsRejectedForACallerWithoutTheAdministrativeRole() {
        authenticate("operator", "ROLE_SERVICE");
        DeadLetterReplayEndpoint endpoint = context.getBean(DeadLetterReplayEndpoint.class);

        assertThatThrownBy(() -> endpoint.replay(request(CONTRACTED_DEAD_LETTER_TOPIC)))
                .isInstanceOf(AccessDeniedException.class);
        verify(replayService, never()).replay(anyString(), anyInt(), anyInt());
    }

    @Test
    void theReplayIsRejectedForADeadLetterTopicOutsideTheTopicContract() {
        authenticate("operator", "ROLE_ADMIN");
        DeadLetterReplayEndpoint endpoint = context.getBean(DeadLetterReplayEndpoint.class);

        assertThatThrownBy(() -> endpoint.replay(request(UNCONTRACTED_DEAD_LETTER_TOPIC)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(replayService, never()).replay(anyString(), anyInt(), anyInt());
    }

    @Test
    void theReplayIsRejectedForATopicThatIsNotADeadLetterTopic() {
        authenticate("operator", "ROLE_ADMIN");
        DeadLetterReplayEndpoint endpoint = context.getBean(DeadLetterReplayEndpoint.class);

        assertThatThrownBy(() -> endpoint.replay(request(KafkaTopicContract.SHOP_ORDER_LIFECYCLE)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(replayService, never()).replay(anyString(), anyInt(), anyInt());
    }

    @Test
    void anAdministratorReplaysAContractedDeadLetterTopic() {
        authenticate("operator", "ROLE_ADMIN");
        when(replayService.replay(CONTRACTED_DEAD_LETTER_TOPIC, 10, 3))
                .thenReturn(new DeadLetterReplayService.ReplayResult(2, 0, 0,
                        java.util.List.of(KafkaTopicContract.SHOP_ORDER_LIFECYCLE)));
        DeadLetterReplayEndpoint endpoint = context.getBean(DeadLetterReplayEndpoint.class);

        DeadLetterReplayEndpoint.ReplayResponse response =
                endpoint.replay(request(CONTRACTED_DEAD_LETTER_TOPIC));

        assertThat(response.replayed()).isEqualTo(2);
        assertThat(response.replayedTopics()).contains(KafkaTopicContract.SHOP_ORDER_LIFECYCLE);
        verify(replayService).replay(CONTRACTED_DEAD_LETTER_TOPIC, 10, 3);
    }

    private DeadLetterReplayEndpoint.ReplayRequest request(String deadLetterTopic) {
        return new DeadLetterReplayEndpoint.ReplayRequest(deadLetterTopic, 0, 0);
    }

    private void authenticate(String principal, String... authorities) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, "n/a", AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Configuration
    @EnableMethodSecurity
    static class SecuredEndpointConfig {

        @Bean
        DeadLetterReplayEndpoint deadLetterReplayEndpoint(DeadLetterReplayService replayService) {
            return new DeadLetterReplayEndpoint(replayService);
        }
    }
}
