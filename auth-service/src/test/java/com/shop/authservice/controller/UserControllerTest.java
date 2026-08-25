package com.shop.authservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @Test
    void getUserByIdReturnsUser() throws Exception {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").build();
        when(userService.findById(id)).thenReturn(user);
        when(userMapper.toResponse(user))
                .thenReturn(UserResponse.builder().id(id).username("alice").build());

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void getAllUsersReturnsPage() throws Exception {
        when(userService.findAllUsers(0, 10, "id", "ASC"))
                .thenReturn(new PageImpl<>(List.of(
                        UserResponse.builder().id(UUID.randomUUID()).username("alice").build())));

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "10")
                        .param("sortBy", "id")
                        .param("sortOrder", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].username").value("alice"));
    }

    @Test
    void changePasswordDelegatesToService() throws Exception {
        when(userService.changePassword(any(ChangePasswordRequest.class)))
                .thenReturn("Password changed successfully");

        String body = "{\"oldPassword\":\"Oldpass1\",\"newPassword\":\"Newpass1\",\"confirmPassword\":\"Newpass1\"}";

        mockMvc.perform(put("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully"));

        verify(userService).changePassword(any(ChangePasswordRequest.class));
    }

    @Test
    void restoreUserDelegatesToService() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.restore(id)).thenReturn("User restored successfully");

        mockMvc.perform(put("/api/v1/users/{id}/restore", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User restored successfully"));

        verify(userService).restore(eq(id));
    }
}