package com.shop.authservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.authservice.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoleController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private RoleService roleService;

    @Test
    void assignRoleReturnsTrue() throws Exception {
        UUID userId = UUID.randomUUID();
        when(roleService.assignRole(eq(userId), eq("PM"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/roles/users/{userId}/assign", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"PM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void revokeRoleReturnsFalse() throws Exception {
        UUID userId = UUID.randomUUID();
        when(roleService.revokeRole(eq(userId), eq("PM"))).thenReturn(false);

        mockMvc.perform(post("/api/v1/roles/users/{userId}/revoke", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"PM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    void getUserRolesReturnsRoleNames() throws Exception {
        UUID userId = UUID.randomUUID();
        when(roleService.getUserRoles(userId)).thenReturn(List.of("ADMIN", "PM"));

        mockMvc.perform(get("/api/v1/roles/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data[1]").value("PM"));
    }

    @Test
    void assignRoleRejectsBlankRoleName() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/roles/users/{userId}/assign", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}