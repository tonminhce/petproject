package com.shop.orderservice.controller;

import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix for GET /api/v1/orders/verify-purchase — the SERVICE-facing
 * rating-eligibility probe consumed by rating-service. Endpoint is @PreAuthorize
 * hasAnyRole('SERVICE','ADMIN'); ineligible purchases are an EMPTY page (200),
 * never a 404.
 */
@WebMvcTest(value = OrderController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class VerifyPurchaseEndpointTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000007001");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000007002");

    private OrderItemResponse item() {
        return new OrderItemResponse(productId, "Widget", 2,
            new BigDecimal("19.99"), new BigDecimal("39.98"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceClient() {
        return jwt().jwt(j -> j.subject("rating-service"))
            .authorities(createAuthorityList("ROLE_SERVICE"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject(userId.toString()))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    private String url() {
        return "/api/v1/orders/verify-purchase?userId=" + userId + "&productId=" + productId;
    }

    // --- authorized roles ---

    @Test
    void verifyPurchase_serviceRole_returns200WithItems() throws Exception {
        when(orderService.findDeliveredItemsByUserAndProduct(
                eq(userId), eq(productId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(item())));

        mockMvc.perform(get(url()).with(serviceClient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].productId").value(productId.toString()))
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void verifyPurchase_serviceRole_returns200WithEmptyPage_whenNoDeliveredPurchase() throws Exception {
        when(orderService.findDeliveredItemsByUserAndProduct(
                eq(userId), eq(productId), any(Pageable.class)))
            .thenReturn(Page.empty());

        mockMvc.perform(get(url()).with(serviceClient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void verifyPurchase_adminRole_returns200() throws Exception {
        when(orderService.findDeliveredItemsByUserAndProduct(
                eq(userId), eq(productId), any(Pageable.class)))
            .thenReturn(Page.empty());

        mockMvc.perform(get(url()).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void verifyPurchase_passesCappedPageable() throws Exception {
        when(orderService.findDeliveredItemsByUserAndProduct(
                eq(userId), eq(productId), any(Pageable.class)))
            .thenReturn(Page.empty());

        mockMvc.perform(get(url() + "&page=1&size=5000").with(serviceClient()))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(orderService).findDeliveredItemsByUserAndProduct(
            eq(userId), eq(productId), eq(PageRequest.of(1, com.shop.common.core.constants.PageableConstant.MAX_PAGE_SIZE)));
    }

    // --- security matrix ---

    @Test
    void verifyPurchase_customerRole_returns403() throws Exception {
        mockMvc.perform(get(url()).with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void verifyPurchase_noAuth_returns401() throws Exception {
        mockMvc.perform(get(url()))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }
}
