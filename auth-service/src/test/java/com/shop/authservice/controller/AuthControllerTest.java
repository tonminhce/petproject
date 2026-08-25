package com.shop.authservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.authservice.dto.request.LoginRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.response.TokenResponse;
import com.shop.authservice.service.AuthService;
import com.shop.authservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @Test
    void signUpRegistersUser() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Alice Wonder");
        req.setUsername("alice123");
        req.setPassword("Passw0rd");
        req.setEmail("alice@example.com");
        req.setGender("female");
        req.setPhone("0901234567");

        mockMvc.perform(post("/api/v1/auth/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User registered successfully"));

        verify(userService).register(any(RegisterRequest.class));
    }

    @Test
    void loginReturnsTokens() throws Exception {
        when(authService.login(eq("alice"), eq("Passw0rd")))
                .thenReturn(TokenResponse.builder()
                        .accessToken("at")
                        .refreshToken("rt")
                        .tokenType("Bearer")
                        .expiresIn(300L)
                        .build());

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("Passw0rd");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("at"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    void refreshReturnsTokens() throws Exception {
        when(authService.refreshToken("refresh-1"))
                .thenReturn(TokenResponse.builder().accessToken("at2").build());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("at2"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        verify(authService).logout("refresh-1");
    }
}