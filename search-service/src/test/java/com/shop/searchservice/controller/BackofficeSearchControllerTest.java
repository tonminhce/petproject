package com.shop.searchservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.searchservice.dto.response.ReindexResponse;
import com.shop.searchservice.service.ReindexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix + wire contract for POST /api/v1/backoffice/search/reindex
 * (spec D5): class @PreAuthorize ADMIN, optional {@code {dryRun:boolean}} body,
 * 409 SRH-12001 while another reindex holds the in-process lock.
 */
@WebMvcTest(value = BackofficeSearchController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeSearchControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ReindexService reindexService;
    @MockitoBean JwtDecoder jwtDecoder;

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007001"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- security matrix ---

    @Test
    void reindex_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/search/reindex"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(reindexService);
    }

    @Test
    void reindex_customerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/search/reindex").with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(reindexService);
    }

    // --- happy paths ---

    @Test
    void reindex_noBody_runsFullReindex() throws Exception {
        when(reindexService.reindex(false))
            .thenReturn(new ReindexResponse(42, "products-v2", 1500));

        mockMvc.perform(post("/api/v1/backoffice/search/reindex").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.indexed").value(42))
            .andExpect(jsonPath("$.data.indexName").value("products-v2"))
            .andExpect(jsonPath("$.data.tookMs").value(1500));

        verify(reindexService).reindex(false);
    }

    @Test
    void reindex_emptyBody_runsFullReindex() throws Exception {
        when(reindexService.reindex(false)).thenReturn(new ReindexResponse(0, "products-v1", 5));

        mockMvc.perform(post("/api/v1/backoffice/search/reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(admin()))
            .andExpect(status().isOk());

        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(reindexService).reindex(captor.capture());
        assertThat(captor.getValue()).isFalse();
    }

    @Test
    void reindex_dryRunBody_forwardsTrue() throws Exception {
        when(reindexService.reindex(true)).thenReturn(new ReindexResponse(7, "products-v1", 100));

        mockMvc.perform(post("/api/v1/backoffice/search/reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}")
                .with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.indexed").value(7));

        verify(reindexService).reindex(true);
    }

    // --- error mapping ---

    @Test
    void reindex_alreadyRunning_returns409WithSrh12001() throws Exception {
        when(reindexService.reindex(false))
            .thenThrow(BusinessException.of(ErrorCode.SEARCH_REINDEX_IN_PROGRESS));

        mockMvc.perform(post("/api/v1/backoffice/search/reindex").with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SRH-12001"));
    }

    @Test
    void reindex_sourceOrEsFailure_returns503WithSrh12002() throws Exception {
        when(reindexService.reindex(false))
            .thenThrow(BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED));

        mockMvc.perform(post("/api/v1/backoffice/search/reindex").with(admin()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SRH-12002"));
    }
}
