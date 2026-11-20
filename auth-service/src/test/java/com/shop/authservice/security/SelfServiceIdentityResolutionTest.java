package com.shop.authservice.security;

import com.shop.authservice.entity.User;
import com.shop.authservice.entity.UserAddress;
import com.shop.authservice.repository.UserAddressRepository;
import com.shop.authservice.repository.UserRepository;
import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "shop.security.issuer-uri=http://localhost:9999/realms/test",
                "shop.jpa.auditing.enabled=false"
        })
@AutoConfigureMockMvc
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SelfServiceIdentityResolutionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withStartupTimeout(Duration.ofMinutes(5))
            .withDatabaseName("auth_identity")
            .withUsername("auth")
            .withPassword("auth");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private String identityProviderUserId;
    private User seededUser;

    @BeforeEach
    void seedUser() {
        identityProviderUserId = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        seededUser = userRepository.saveAndFlush(User.builder()
                .fullName("Alice Wonder")
                .username("alice-" + suffix)
                .email("alice-" + suffix + "@example.com")
                .gender("female")
                .phone("09" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100_000_000L)))
                .keycloakUserId(identityProviderUserId)
                .build());
    }

    @Test
    @DisplayName("the token subject resolves the platform user through the identity-provider column")
    void tokenSubjectResolvesThePlatformUserThroughTheIdentityProviderColumn() throws Exception {
        assertThat(seededUser.getId()).isNotEqualTo(UUID.fromString(identityProviderUserId));

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject(identityProviderUserId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullname").value("Alice Updated"));

        User persisted = userRepository.findByKeycloakUserId(identityProviderUserId).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(seededUser.getId());
        assertThat(persisted.getFullName()).isEqualTo("Alice Updated");
    }

    @Test
    @DisplayName("the address row is keyed by the surrogate key of the resolved user row")
    void addressRowIsKeyedByTheSurrogateKeyOfTheResolvedUserRow() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(j -> j.subject(identityProviderUserId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recipientName":"Alice Wonder",
                                  "phoneNumber":"0901234567",
                                  "province":"Ho Chi Minh",
                                  "district":"District 1",
                                  "ward":"Ward 1",
                                  "detailAddress":"1 Nguyen Hue",
                                  "isDefault":true
                                }
                                """))
                .andExpect(status().isCreated());

        UserAddress address = userAddressRepository
                .findByUserIdOrderByIsDefaultDescCreatedAtDesc(seededUser.getId()).getFirst();
        assertThat(address.getUserId()).isEqualTo(seededUser.getId());
        assertThat(address.getRecipientName()).isEqualTo("Alice Wonder");
    }

    @Test
    @DisplayName("a token subject that no user row carries is rejected")
    void tokenSubjectWithoutAPlatformUserIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice Updated\"}"))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findByKeycloakUserId(identityProviderUserId).orElseThrow().getFullName())
                .isEqualTo("Alice Wonder");
    }
}
