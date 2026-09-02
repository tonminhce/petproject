package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.productservice.service.ProductMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix + wire contract for the H-4 internal reference-count
 * endpoint: SERVICE token only. Anonymous → 401 (resource-server chain);
 * USER/ADMIN tokens → 403 — deliberately denied even to admins, an internal
 * machine endpoint is not for humans; SERVICE → 200 with
 * {@code {mediaId, referenceCount}} over the fleet ApiResponse envelope.
 */
@WebMvcTest(value = InternalProductMediaController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({SecurityAutoConfiguration.class, InternalProductMediaControllerTest.TestWebSecurityConfig.class})
class InternalProductMediaControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductMediaService productMediaService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID MEDIA_ID = UUID.fromString("c1000000-0000-0000-0000-000000000001");

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000009001"))
            .authorities(createAuthorityList("ROLE_SERVICE"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000009002"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor user() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000009003"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- security matrix ---

    @Test
    void referenceCount_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}", MEDIA_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(productMediaService);
    }

    @Test
    void referenceCount_userRole_returns403() throws Exception {
        mockMvc.perform(get(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}", MEDIA_ID).with(user()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(productMediaService);
    }

    @Test
    void referenceCount_adminRole_returns403() throws Exception {
        mockMvc.perform(get(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}", MEDIA_ID).with(admin()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(productMediaService);
    }

    // --- SERVICE token: the wire contract ---

    @Test
    void referenceCount_serviceRole_returns200WithCount() throws Exception {
        when(productMediaService.referenceCount(MEDIA_ID)).thenReturn(3L);

        mockMvc.perform(get(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}", MEDIA_ID).with(service()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.mediaId").value(MEDIA_ID.toString()))
            .andExpect(jsonPath("$.data.referenceCount").value(3));
    }

    @Test
    void referenceCount_zeroReferences_returnsZeroNotOmitted() throws Exception {
        when(productMediaService.referenceCount(MEDIA_ID)).thenReturn(0L);

        mockMvc.perform(get(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/{mediaId}", MEDIA_ID).with(service()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.referenceCount").value(0));
    }
}
