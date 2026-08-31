package com.shop.searchservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.searchservice.dto.request.SearchParams;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.service.SearchQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = SearchController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class SearchControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean SearchQueryService searchQueryService;
    @MockitoBean JwtDecoder jwtDecoder;

    private ProductSearchResponse response() {
        return new ProductSearchResponse(
            UUID.fromString("a1000000-0000-0000-0000-000000000001"), "Laser Mouse",
            "LogiTech", "Peripherals", "laser-mouse", "http://img.example/mouse.png",
            new BigDecimal("10.00"), new BigDecimal("4.5"), 2);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000009001"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- anonymous ---

    @Test
    void search_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(searchQueryService);
    }

    // --- happy path ---

    @Test
    void search_returns200WithPageShapeAndDefaultParams() throws Exception {
        when(searchQueryService.search(anyParams())).thenReturn(
            PageResponse.of(List.of(response()), 0, 20, 5));

        mockMvc.perform(get("/api/v1/search").with(customer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value("a1000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.data.content[0].title").value("Laser Mouse"))
            .andExpect(jsonPath("$.data.content[0].brandName").value("LogiTech"))
            .andExpect(jsonPath("$.data.content[0].price").value(10.00))
            .andExpect(jsonPath("$.data.content[0].avgRating").value(4.5))
            .andExpect(jsonPath("$.data.totalElements").value(5));

        ArgumentCaptor<SearchParams> captor = ArgumentCaptor.forClass(SearchParams.class);
        verify(searchQueryService).search(captor.capture());
        assertThat(captor.getValue().getPage()).isEqualTo(0);
        assertThat(captor.getValue().getSize()).isEqualTo(20);
        assertThat(captor.getValue().getQ()).isNull();
    }

    @Test
    void search_forwardsQueryAndFilters() throws Exception {
        when(searchQueryService.search(anyParams())).thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/search")
                    .param("q", "mouse")
                    .param("brandId", "b1000000-0000-0000-0000-00000000000a")
                    .param("categoryId", "c1000000-0000-0000-0000-00000000000a")
                    .param("minPrice", "10")
                    .param("maxPrice", "50")
                    .param("minRating", "3.5")
                    .param("sort", "price_asc")
                    .param("page", "2")
                    .param("size", "50")
                    .with(customer()))
            .andExpect(status().isOk());

        ArgumentCaptor<SearchParams> captor = ArgumentCaptor.forClass(SearchParams.class);
        verify(searchQueryService).search(captor.capture());
        SearchParams forwarded = captor.getValue();
        assertThat(forwarded.getQ()).isEqualTo("mouse");
        assertThat(forwarded.getBrandId()).isEqualTo(UUID.fromString("b1000000-0000-0000-0000-00000000000a"));
        assertThat(forwarded.getCategoryId()).isEqualTo(UUID.fromString("c1000000-0000-0000-0000-00000000000a"));
        assertThat(forwarded.getMinPrice()).isEqualByComparingTo("10");
        assertThat(forwarded.getMaxPrice()).isEqualByComparingTo("50");
        assertThat(forwarded.getMinRating()).isEqualByComparingTo("3.5");
        assertThat(forwarded.getSort()).isEqualTo("price_asc");
        assertThat(forwarded.getPage()).isEqualTo(2);
        assertThat(forwarded.getSize()).isEqualTo(50);
    }

    // --- validation ---

    @Test
    void search_qOver200Chars_returns400WithErr0422V() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                    .param("q", "x".repeat(201))
                    .with(customer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"))
            .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(searchQueryService);
    }

    @Test
    void search_sizeOverCap_returns400WithErr0422V() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                    .param("size", "201")
                    .with(customer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(searchQueryService);
    }

    @Test
    void search_unknownSortValue_returns400WithErr0422V() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                    .param("sort", "popularity")
                    .with(customer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(searchQueryService);
    }

    @Test
    void search_negativePage_returns400WithErr0422V() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                    .param("page", "-1")
                    .with(customer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(searchQueryService);
    }

    // --- ES-down mapping ---

    @Test
    void search_esDown_returns503WithSrh12002() throws Exception {
        when(searchQueryService.search(anyParams()))
            .thenThrow(BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED));

        mockMvc.perform(get("/api/v1/search")
                    .param("q", "mouse")
                    .with(customer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SRH-12002"));
    }

    private static SearchParams anyParams() {
        return org.mockito.ArgumentMatchers.any(SearchParams.class);
    }
}
